package com.storagehub.service.renewal.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.PublishedRenewalPolicy;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes one complete JSON snapshot in existing shared settings; no seed or shadow Manager policy. */
@Service @RequiredArgsConstructor
public class RenewalPolicyPublicationService {
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final DuongResourceAccess access;
    private final AuditLogService audit;
    public static String key(UUID facility){return "rentalRenewalPolicy:"+facility;}
    public record Stored(String reference,PublishedRenewalPolicy policy) {}
    public Optional<Stored> read(UUID facility,LocalDate applicableDate) {
        if(facility==null)return Optional.empty();
        var query=em.createQuery("select s from SystemSetting s where s.settingKey=:key",SystemSetting.class).setParameter("key",key(facility));
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())query.setLockMode(LockModeType.PESSIMISTIC_READ);
        var rows=query.getResultList();
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()!=1)throw ApiExceptions.conflict("Renewal policy source is ambiguous");
        var p=decode(rows.getFirst(),facility);
        if(applicableDate!=null&&(applicableDate.isBefore(p.effectiveFrom())||p.effectiveTo()!=null&&applicableDate.isAfter(p.effectiveTo())))return Optional.empty();
        return Optional.of(new Stored("system-setting:"+rows.getFirst().getId(),p));
    }
    @Transactional(readOnly=true)
    public PublishedRenewalPolicy get(ActorPrincipal actor,UUID facility) {
        var role=actor!=null&&actor.hasRole(RoleCode.BUSINESS)?RoleCode.BUSINESS:RoleCode.MANAGER;
        access.require(actor,role,facility,FacilityScopeLevel.READ,SystemPermission.VIEW_POLICIES);
        return read(facility,null).orElseThrow(()->ApiExceptions.notFound("Renewal policy has not been published")).policy();
    }
    @Transactional
    public PublishedRenewalPolicy publish(ActorPrincipal actor,UUID facility,PublishedRenewalPolicy.Input input) {
        var user=access.require(actor,RoleCode.BUSINESS,facility,FacilityScopeLevel.MANAGE,SystemPermission.VIEW_POLICIES,SystemPermission.MANAGE_POLICIES);
        if(em.find(Facility.class,facility,LockModeType.PESSIMISTIC_WRITE)==null)throw ApiExceptions.notFound("Facility not found");
        validate(input);
        for(var id:input.eligiblePackageIds()) {
            var pack=em.find(RentalPackagePolicy.class,id);
            if(pack==null||!Objects.equals(pack.getFacility().getId(),facility))throw ApiExceptions.validation("Eligible package must belong to this facility",null);
        }
        var rows=em.createQuery("select s from SystemSetting s where s.settingKey=:key",SystemSetting.class).setParameter("key",key(facility)).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        var setting=rows.isEmpty()?null:rows.getFirst();var before=setting==null?null:decode(setting,facility);
        long revision=before==null?0:before.revision();
        if(input.expectedRevision()!=revision)throw ApiExceptions.conflict("Renewal policy revision changed; reload required");
        var p=new PublishedRenewalPolicy("RENEWAL_POLICY_V1",facility,Math.addExact(revision,1),UUID.randomUUID().toString(),user.getId(),Instant.now(),
            input.effectiveFrom(),input.effectiveTo(),input.quoteTtlMinutes(),input.paymentWindowHours(),input.requestWindowDays(),input.depositRate(),
            Set.copyOf(input.eligiblePackageIds()),input.signing(),input.term());
        var json=encode(p);if(json.length()>2000)throw ApiExceptions.validation("Policy exceeds existing settings storage limit",null);
        if(setting==null){setting=new SystemSetting();setting.setSettingKey(key(facility));setting.setGroupName("Chính sách gia hạn");setting.setLabel("Chính sách gia hạn theo cơ sở");setting.setSettingType("text");setting.setValue(json);em.persist(setting);}
        setting.setValue(json);em.flush();
        audit.recordMutation(user,"RENEWAL_POLICY_PUBLISHED","SystemSetting",setting.getId(),facility,before,p);
        return p;
    }
    private PublishedRenewalPolicy decode(SystemSetting setting,UUID facility) {
        try {
            var tree=mapper.readTree(setting.getValue());
            if(tree==null||!tree.hasNonNull("quoteTtlMinutes")||!tree.hasNonNull("paymentWindowHours")||!tree.hasNonNull("requestWindowDays"))throw ApiExceptions.conflict("Published renewal policy fields are incomplete");
            var p=mapper.readValue(setting.getValue(),PublishedRenewalPolicy.class);
            if(p==null||!"RENEWAL_POLICY_V1".equals(p.schema())||!facility.equals(p.facilityId())||p.revision()<1||p.version()==null||p.version().isBlank()||p.publishedBy()==null||p.publishedAt()==null)throw ApiExceptions.conflict("Published renewal policy metadata is incomplete");
            validate(new PublishedRenewalPolicy.Input(p.revision(),p.effectiveFrom(),p.effectiveTo(),p.quoteTtlMinutes(),p.paymentWindowHours(),p.requestWindowDays(),p.depositRate(),p.eligiblePackageIds(),p.signing(),p.term()));
            return p;
        }catch(JsonProcessingException e){throw ApiExceptions.conflict("Published renewal policy is invalid");}
    }
    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Policy serialization failed",e);}}
    public static void validate(PublishedRenewalPolicy.Input p) {
        if(p==null||p.expectedRevision()==null||p.expectedRevision()<0||p.effectiveFrom()==null||p.effectiveTo()!=null&&p.effectiveTo().isBefore(p.effectiveFrom())
            ||p.quoteTtlMinutes()==null||p.quoteTtlMinutes()<1||p.paymentWindowHours()==null||p.paymentWindowHours()<1||p.requestWindowDays()==null||p.requestWindowDays()<0
            ||p.depositRate()==null||p.depositRate().signum()<0||p.depositRate().compareTo(java.math.BigDecimal.ONE)>0||p.eligiblePackageIds()==null||p.eligiblePackageIds().size()>20||p.eligiblePackageIds().stream().anyMatch(Objects::isNull))
            throw ApiExceptions.validation("Incomplete or invalid renewal policy",null);
        var s=p.signing();if(s!=null&&(s.signingWindowMinutes()==null||s.signingWindowMinutes()<1||s.exceptionExtensionLimitMinutes()==null||s.exceptionExtensionLimitMinutes()<1))throw ApiExceptions.validation("Invalid signing policy",null);
        var t=p.term();if(t!=null&&(!"CALENDAR_DAYS".equals(t.calendar())||!"Asia/Ho_Chi_Minh".equals(t.timezone())||t.warningThroughDay()==null||t.seriousThroughDay()==null||t.urgentThroughDay()==null||t.recoveryFromDay()==null
            ||t.warningThroughDay()<1||t.seriousThroughDay()<=t.warningThroughDay()||t.urgentThroughDay()<=t.seriousThroughDay()||t.recoveryFromDay()!=t.urgentThroughDay()+1||t.recoveryCutoffTime()==null||t.recoveryStartTime()==null))throw ApiExceptions.validation("Invalid calendar-day overdue policy",null);
    }
}
