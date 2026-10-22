package com.storagehub.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.support.SupportCommands.Visibility;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.renewal.integration.RenewalAssignmentSource;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.support.SupportSources;
import jakarta.persistence.*;
import java.nio.file.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Dedicated file types. Existing shared file types/guards never enter this handler. */
@Component @RequiredArgsConstructor
public class DuongFileEvidence implements RenewalOperationSources.EvidenceSource,SupportSources.EvidenceSource {
    private final EntityManager em;
    private final DuongResourceAccess access;
    private final RenewalAssignmentSource assignments;
    private final FileProperties properties;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.overdue.RecoveryFileEvidence> recoveryEvidence;
    private static final Set<String> PURPOSES=Set.of("ARRIVAL","INCIDENT","SIGNED_CONTRACT","CASH_RECEIPT","INSTALLMENT_RECEIPT","EXCEPTION","FAULT_REVIEW","REFUND");
    public static final String PUBLIC="DUONG_SUPPORT_PUBLIC",INTERNAL="DUONG_SUPPORT_INTERNAL",UPLOAD="DUONG_SUPPORT_UPLOAD";
    public static String renewalType(String purpose){if(!PURPOSES.contains(purpose))throw ApiExceptions.validation("Unsupported Renewal evidence purpose",null);return "DUONG_RENEWAL_"+purpose;}
    public static boolean handles(String type){return type!=null&&(com.storagehub.service.overdue.RecoveryFileEvidence.handles(type)||PUBLIC.equals(type)||INTERNAL.equals(type)||UPLOAD.equals(type)||PURPOSES.stream().anyMatch(p->renewalType(p).equals(type)));}
    public boolean atomic(){return true;} // All binding writes are local, locked and in the caller transaction.
    @Transactional(readOnly=true)
    public boolean supportsFiles(List<UUID> files) {
        return files!=null&&files.stream().allMatch(id->{var f=id==null?null:em.find(FileAsset.class,id);return f!=null&&(PUBLIC.equals(f.getEntityType())||INTERNAL.equals(f.getEntityType()));});
    }
    public void requireUpload(ActorPrincipal actor,String type,UUID resource) {
        if(com.storagehub.service.overdue.RecoveryFileEvidence.handles(type)){recoveryFiles().upload(actor,type,resource);return;}
        if(type.startsWith("DUONG_RENEWAL_")) {
            if(type.equals("DUONG_RENEWAL_INSTALLMENT_RECEIPT")){
                var n=renewal(resource);access.require(actor,RoleCode.STAFF,n.getRental().getFacility().getId(),FacilityScopeLevel.OPERATE,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_PAYMENTS);
                if(n.getStatus()!=RenewalStatus.completed)throw ApiExceptions.conflict("Chỉ lưu chứng từ kỳ sau khi gia hạn đã ký.");return;
            }
            var n=renewal(resource);renewalAccess(actor,n,type.substring("DUONG_RENEWAL_".length()),true);
            if(Set.of(RenewalStatus.pending,RenewalStatus.accepted,RenewalStatus.cancelled,RenewalStatus.rejected,RenewalStatus.completed,RenewalStatus.payment_expired).contains(n.getStatus()))throw ApiExceptions.conflict("Renewal is not accepting operational evidence before payment/signing");
        }else {
            if(actor!=null&&actor.hasRole(RoleCode.MANAGER)&&!INTERNAL.equals(type))throw ApiExceptions.forbidden("Manager may only upload internal Support evidence");
            if(UPLOAD.equals(type)){access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);if(resource!=null&&!actor.userId().equals(resource))throw unavailable();return;}
            if(actor!=null&&actor.hasRole(RoleCode.CUSTOMER)&&PUBLIC.equals(type)&&resource==null){access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);return;}
            supportAccess(actor,ticket(resource),INTERNAL.equals(type)?Visibility.INTERNAL:Visibility.PUBLIC,true);
        }
    }
    @Transactional(readOnly=true)
    public void requireDownload(ActorPrincipal actor,FileAsset file) {
        if(file!=null&&com.storagehub.service.overdue.RecoveryFileEvidence.handles(file.getEntityType())){recoveryFiles().download(actor,file);return;}
        available(file);
        if(file.getEntityType().startsWith("DUONG_RENEWAL_")) {
            var n=renewal(file.getEntityId());
            if(actor!=null&&actor.hasRole(RoleCode.CUSTOMER)) {
                access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);
                if(!Objects.equals(n.getRental().getCustomer().getId(),actor.userId()))throw unavailable();
                String purpose=file.getEntityType().substring("DUONG_RENEWAL_".length());
                boolean bound=switch(purpose) {
                    case "SIGNED_CONTRACT" -> n.getStatus()==RenewalStatus.completed && boundToEvent(n.getId(),"COMPLETION","signedDocumentFileId",file.getId());
                    case "ARRIVAL" -> boundToEvent(n.getId(),"ARRIVAL","evidenceFileIds",file.getId());
                    case "CASH_RECEIPT" -> boundToEvent(n.getId(),"CASH","receiptFileId",file.getId());
                    case "INSTALLMENT_RECEIPT" -> boundToEvent(n.getId(),"INSTALLMENT_PAYMENT","receiptFileId",file.getId());
                    default -> false;
                };
                if(!bound)throw unavailable();
            }else renewalAccess(actor,n,file.getEntityType().substring("DUONG_RENEWAL_".length()),false);
        }else {
            if(UPLOAD.equals(file.getEntityType())) { // Private staging has a non-null owner binding, never eligible for complaint rebind.
                access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);
                if(!Objects.equals(file.getEntityId(),actor.userId())||!Objects.equals(file.getUploadedBy().getId(),actor.userId()))throw unavailable();return;
            }
            supportAccess(actor,ticket(file.getEntityId()),INTERNAL.equals(file.getEntityType())?Visibility.INTERNAL:Visibility.PUBLIC,false);
        }
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void require(ActorPrincipal actor,Renewal n,String purpose,List<UUID> files) {
        if(files==null)throw ApiExceptions.validation("Evidence list required",null);
        renewalAccess(actor,n,purpose,true);
        for(var id:ordered(files)) {
            var f=em.find(FileAsset.class,id,LockModeType.PESSIMISTIC_READ);available(f);
            if(!renewalType(purpose).equals(f.getEntityType())||!Objects.equals(n.getId(),f.getEntityId())||!Objects.equals(actor.userId(),f.getUploadedBy().getId()))throw unavailable();
        }
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireAttach(ActorPrincipal actor,SupportTicket t,Visibility visibility,List<UUID> files) {
        supportAccess(actor,t,visibility,true);
        for(var id:ordered(files)) {
            var f=em.find(FileAsset.class,id,LockModeType.PESSIMISTIC_WRITE);available(f);
            if(visibility==Visibility.PUBLIC&&UPLOAD.equals(f.getEntityType())&&actor.hasRole(RoleCode.CUSTOMER)
                &&Objects.equals(actor.userId(),f.getUploadedBy().getId())&&Objects.equals(actor.userId(),f.getEntityId())) {
                f.setEntityType(PUBLIC);f.setEntityId(t.getId());continue;
            }
            if(!(visibility==Visibility.INTERNAL?INTERNAL:PUBLIC).equals(f.getEntityType())||!Objects.equals(actor.userId(),f.getUploadedBy().getId())||!Objects.equals(t.getId(),f.getEntityId()))throw unavailable();
            f.setEntityId(t.getId()); // Never rebind an already bound file or promote INTERNAL to PUBLIC.
        }
    }
    @Transactional(readOnly=true)
    public void requireRead(ActorPrincipal actor,SupportTicket t,Visibility visibility,List<UUID> files) {
        supportAccess(actor,t,visibility,false);
        for(var id:ordered(files)) {
            var f=em.find(FileAsset.class,id);available(f);
            if(!(visibility==Visibility.INTERNAL?INTERNAL:PUBLIC).equals(f.getEntityType())||!Objects.equals(t.getId(),f.getEntityId()))throw unavailable();
        }
    }
    private List<UUID> ordered(List<UUID> files){if(files==null||files.size()>10||files.stream().anyMatch(Objects::isNull)||new HashSet<>(files).size()!=files.size())throw ApiExceptions.validation("Invalid evidence file list",null);return files.stream().sorted().toList();}
    private Renewal renewal(UUID id){var n=id==null?null:em.find(Renewal.class,id);if(n==null||n.getRental()==null||n.getRental().getCustomer()==null||n.getRental().getFacility()==null||n.getRequestedBy()==null||!Objects.equals(n.getRequestedBy().getId(),n.getRental().getCustomer().getId()))throw unavailable();return n;}
    private SupportTicket ticket(UUID id){var t=id==null?null:em.find(SupportTicket.class,id);if(t==null||t.getFacility()==null||t.getCustomer()==null)throw unavailable();return t;}
    private void renewalAccess(ActorPrincipal actor,Renewal n,String purpose,boolean write) {
        if("INSTALLMENT_RECEIPT".equals(purpose)&&!write){
            var role=actor!=null&&actor.hasRole(RoleCode.MANAGER)?RoleCode.MANAGER:RoleCode.STAFF;
            access.require(actor,role,n.getRental().getFacility().getId(),FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);return;
        }
        if(actor!=null&&actor.hasRole(RoleCode.MANAGER)) {
            access.require(actor,RoleCode.MANAGER,n.getRental().getFacility().getId(),write?FacilityScopeLevel.MANAGE:FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
            if(write){if(!Set.of("EXCEPTION","FAULT_REVIEW","REFUND").contains(purpose))throw ApiExceptions.forbidden("Signing evidence is recorded by assigned Staff");assignments.require(actor,n,purpose.equals("REFUND")?RenewalOperationSources.Capability.REFUND:RenewalOperationSources.Capability.EXCEPTION);}
        }else assignments.require(actor,n,write?(purpose.equals("INCIDENT")?RenewalOperationSources.Capability.INCIDENT:purpose.equals("CASH_RECEIPT")?RenewalOperationSources.Capability.CASH:RenewalOperationSources.Capability.COMPLETE):RenewalOperationSources.Capability.READ);
    }
    private void supportAccess(ActorPrincipal actor,SupportTicket t,Visibility visibility,boolean write) {
        if(visibility==null)throw ApiExceptions.validation("Explicit evidence visibility required",null);
        if(write&&actor!=null&&actor.hasRole(RoleCode.MANAGER)&&visibility!=Visibility.INTERNAL)throw ApiExceptions.forbidden("Manager may only attach internal Support evidence");
        if(actor!=null&&actor.hasRole(RoleCode.CUSTOMER)) {
            access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);
            if(visibility!=Visibility.PUBLIC||!Objects.equals(actor.userId(),t.getCustomer().getId()))throw unavailable();
        }else if(actor!=null&&actor.hasRole(RoleCode.MANAGER)) {
            if(write)access.require(actor,RoleCode.MANAGER,t.getFacility().getId(),FacilityScopeLevel.MANAGE,SystemPermission.VIEW_SUPPORT,SystemPermission.MANAGE_SUPPORT);
            else access.require(actor,RoleCode.MANAGER,t.getFacility().getId(),FacilityScopeLevel.READ,SystemPermission.VIEW_SUPPORT);
        }else {
            if(write)access.require(actor,RoleCode.STAFF,t.getFacility().getId(),FacilityScopeLevel.OPERATE,SystemPermission.VIEW_SUPPORT,SystemPermission.MANAGE_SUPPORT);
            else access.require(actor,RoleCode.STAFF,t.getFacility().getId(),FacilityScopeLevel.OPERATE,SystemPermission.VIEW_SUPPORT);
            if(!Objects.equals(actor.userId(),t.getAssignedTo()==null?null:t.getAssignedTo().getId()))throw unavailable();
        }
        if(write&&t.getStatus()==SupportTicketStatus.closed)throw ApiExceptions.conflict("Closed Support ticket does not accept evidence");
    }
    private void available(FileAsset f) {
        if(f==null||!handles(f.getEntityType())||f.getStatus()!=FileAssetStatus.ACTIVE||f.getUploadedBy()==null||f.getStorageKey()==null||f.getSizeBytes()<1||f.getSizeBytes()>properties.getMaxSizeBytes()||f.getChecksumSha256()==null)throw unavailable();
        try {
            var root=Path.of(properties.getStoragePath()).toRealPath();var path=Path.of(f.getStorageKey()).toRealPath();
            if(!path.startsWith(root)||!Files.isRegularFile(path)||Files.size(path)!=f.getSizeBytes())throw unavailable();
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var in=new java.security.DigestInputStream(Files.newInputStream(path),digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}
            if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(f.getChecksumSha256()))throw ApiExceptions.conflict("Evidence file has changed");
        }catch(java.io.IOException e){throw unavailable();}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private boolean boundToEvent(UUID renewalId,String kind,String key,UUID fileId) {
        var events=em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind=:kind",RenewalOperationEvent.class)
            .setParameter("id",renewalId).setParameter("kind",kind).getResultList();
        for(var event:events)try {
            var node=mapper.readTree(event.getPayloadJson()).path(key);
            if(node.isTextual()&&fileId.toString().equals(node.asText()))return true;
            if(node.isArray())for(var item:node)if(item.isTextual()&&fileId.toString().equals(item.asText()))return true;
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ApiExceptions.conflict("Renewal evidence binding is invalid");}
        return false;
    }
    private RuntimeException unavailable(){return ApiExceptions.notFound("Evidence file/resource not found");}
    private com.storagehub.service.overdue.RecoveryFileEvidence recoveryFiles(){var source=recoveryEvidence==null?null:recoveryEvidence.getIfAvailable();if(source==null)throw ApiExceptions.conflict("Recovery evidence is not enabled");return source;}
}
