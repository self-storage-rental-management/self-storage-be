package com.storagehub.service.overdue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.*;
import java.nio.file.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static com.storagehub.service.overdue.RecoverySql.*;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled",havingValue="true")
public class RecoveryFileEvidence {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final DuongResourceAccess access;
    private final FileProperties properties;
    private final ObjectMapper mapper;
    private static final Set<String> TYPES=Set.of("DUONG_RECOVERY_INSPECTION","DUONG_RECOVERY_COMPLETION","DUONG_RECOVERY_SETTLEMENT");
    public static boolean handles(String type){return TYPES.contains(type==null?"":type);}
    public void upload(ActorPrincipal actor,String type,UUID taskId) {
        var task=task(taskId);authorize(actor,task,true);
        if(!handles(type)||!task.status().equals("IN_PROGRESS"))throw ApiExceptions.conflict("Recovery task is not accepting evidence");
        if(type.equals("DUONG_RECOVERY_SETTLEMENT"))access.require(actor,role(actor),task.facility(),FacilityScopeLevel.OPERATE,SystemPermission.MANAGE_PAYMENTS);
    }
    @Transactional(readOnly=true)
    public void download(ActorPrincipal actor,FileAsset file) {
        available(file);var task=task(file.getEntityId());
        if(actor!=null&&actor.hasRole(RoleCode.CUSTOMER)) {
            access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);
            var rental=em.find(Rental.class,task.rental());
            if(rental==null||!rental.getCustomer().getId().equals(actor.userId())||!attached(task.id(),file.getId()))throw missing();
        }else authorize(actor,task,false);
    }
    public void require(ActorPrincipal actor,UUID taskId,String purpose,List<UUID> ids) {
        if(ids==null||ids.isEmpty()||ids.size()>10||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())
            throw ApiExceptions.validation("Between 1 and 10 distinct evidence files required",null);
        var task=task(taskId);authorize(actor,task,true);
        for(var id:ids.stream().sorted().toList()) {
            var f=em.find(FileAsset.class,id,LockModeType.PESSIMISTIC_READ);available(f);
            if(!("DUONG_RECOVERY_"+purpose).equals(f.getEntityType())||!taskId.equals(f.getEntityId())
                ||!actor.userId().equals(f.getUploadedBy().getId()))throw missing();
        }
    }
    private boolean attached(UUID task,UUID file) {
        if(jdbc.queryForObject("select count(*) from recovery_task_operations where task_id=? and completion_file_id=?",Long.class,bytes(task),bytes(file))>0)return true;
        if(jdbc.queryForObject("select count(*) from recovery_settlement_receipts where task_id=? and receipt_file_id=?",Long.class,bytes(task),bytes(file))>0)return true;
        var json=jdbc.query("select inspection_json from recovery_task_operations where task_id=?",(rs,i)->rs.getString(1),bytes(task));
        if(json.isEmpty()||json.getFirst()==null)return false;
        try{for(var id:mapper.readTree(json.getFirst()).path("evidenceFileIds"))if(file.toString().equals(id.asText()))return true;return false;}
        catch(Exception e){throw ApiExceptions.conflict("Recovery evidence binding is invalid");}
    }
    private void authorize(ActorPrincipal actor,Task task,boolean write) {
        access.require(actor,role(actor),task.facility(),write?FacilityScopeLevel.OPERATE:FacilityScopeLevel.READ,
            write?new SystemPermission[]{SystemPermission.VIEW_RENTALS,SystemPermission.PROCESS_RETURNS}:new SystemPermission[]{SystemPermission.VIEW_RENTALS});
    }
    private static RoleCode role(ActorPrincipal actor){return actor!=null&&actor.hasRole(RoleCode.MANAGER)?RoleCode.MANAGER:RoleCode.STAFF;}
    private record Task(UUID id,UUID rental,UUID facility,String status) {}
    private Task task(UUID id) {
        if(id==null)throw missing();
        return jdbc.query("select rental_id,facility_id,status from recovery_tasks where id=?",
            (rs,i)->new Task(id,uuid(rs.getBytes(1)),uuid(rs.getBytes(2)),rs.getString(3)),bytes(id)).stream().findFirst().orElseThrow(RecoveryFileEvidence::missing);
    }
    private void available(FileAsset file) {
        if(file==null||!handles(file.getEntityType())||file.getStatus()!=FileAssetStatus.ACTIVE||file.getUploadedBy()==null
            ||file.getStorageKey()==null||file.getChecksumSha256()==null||file.getSizeBytes()<1||file.getSizeBytes()>properties.getMaxSizeBytes())throw missing();
        try {
            var root=Path.of(properties.getStoragePath()).toRealPath();var path=Path.of(file.getStorageKey()).toRealPath();
            if(!path.startsWith(root)||!Files.isRegularFile(path)||Files.size(path)!=file.getSizeBytes())throw missing();
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var in=new java.security.DigestInputStream(Files.newInputStream(path),digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}
            if(!HexFormat.of().formatHex(digest.digest()).equals(file.getChecksumSha256()))throw ApiExceptions.conflict("Recovery evidence file has changed");
        }catch(java.io.IOException e){throw missing();}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static RuntimeException missing(){return ApiExceptions.notFound("Recovery evidence/resource not found");}
}
