package com.storagehub.service.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.integration.DuongResourceAccess;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.support.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BO-owned, effective-dated snapshot. No default cooldown/auto-close policy. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.communication.enabled",havingValue="true")
public class CommunicationPolicyService implements SupportSources.ClosePolicySource {
    public record Input(Long expectedRevision,LocalDate effectiveFrom,LocalDate effectiveTo,
        Integer reminderCooldownMinutes,Integer supportReviewDays,Boolean customerMayClose) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String field,Object value){throw ApiExceptions.validation("Unknown communication policy field",null);}
    }
    public record Policy(UUID facilityId,long revision,String version,UUID publishedBy,Instant publishedAt,
        LocalDate effectiveFrom,LocalDate effectiveTo,Integer reminderCooldownMinutes,Integer supportReviewDays,Boolean customerMayClose) {}
    public record Stored(String reference,Policy policy) {}
    private final EntityManager em;private final ObjectMapper mapper;private final DuongResourceAccess access;private final AuditLogService audit;
    private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private String key(UUID facility){return "duongCommunicationPolicy:"+facility;}
    public Optional<Stored> read(UUID facility,Instant now) {
        var q=em.createQuery("select s from SystemSetting s where s.settingKey=:key",SystemSetting.class).setParameter("key",key(facility));
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())q.setLockMode(LockModeType.PESSIMISTIC_READ);
        var rows=q.getResultList();if(rows.isEmpty())return Optional.empty();var p=decode(rows.getFirst(),facility);var date=now.atZone(ZONE).toLocalDate();
        if(p.publishedAt().isAfter(now)||date.isBefore(p.effectiveFrom())||p.effectiveTo()!=null&&date.isAfter(p.effectiveTo()))return Optional.empty();
        return Optional.of(new Stored("system-setting:"+rows.getFirst().getId(),p));
    }
    public Policy get(ActorPrincipal actor,UUID facility){access.require(actor,actor!=null&&actor.hasRole(RoleCode.BUSINESS)?RoleCode.BUSINESS:RoleCode.MANAGER,facility,FacilityScopeLevel.READ,SystemPermission.VIEW_POLICIES);return read(facility,Instant.now()).orElseThrow(()->ApiExceptions.notFound("Communication policy has not been published or is not effective")).policy();}
    @Transactional public Policy publish(ActorPrincipal actor,UUID facility,Input input) {
        var user=access.require(actor,RoleCode.BUSINESS,facility,FacilityScopeLevel.MANAGE,SystemPermission.VIEW_POLICIES,SystemPermission.MANAGE_POLICIES);validate(input);
        if(em.find(Facility.class,facility,LockModeType.PESSIMISTIC_WRITE)==null)throw ApiExceptions.notFound("Facility not found");
        var rows=em.createQuery("select s from SystemSetting s where s.settingKey=:key",SystemSetting.class).setParameter("key",key(facility)).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        var setting=rows.isEmpty()?null:rows.getFirst();var old=setting==null?null:decode(setting,facility);long revision=old==null?0:old.revision();
        if(input.expectedRevision()!=revision)throw ApiExceptions.conflict("Communication policy revision changed");
        var p=new Policy(facility,Math.addExact(revision,1),UUID.randomUUID().toString(),user.getId(),Instant.now(),input.effectiveFrom(),input.effectiveTo(),input.reminderCooldownMinutes(),input.supportReviewDays(),input.customerMayClose());
        String json;try{json=mapper.writeValueAsString(p);}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}if(json.length()>2000)throw ApiExceptions.validation("Policy exceeds existing settings capacity",null);
        if(setting==null){setting=new SystemSetting();setting.setSettingKey(key(facility));setting.setGroupName("Thông báo và hỗ trợ");setting.setLabel("Chính sách nhắc quá hạn và thời gian xem xét hỗ trợ");setting.setSettingType("text");setting.setValue(json);em.persist(setting);}setting.setValue(json);em.flush();audit.recordMutation(user,"COMMUNICATION_POLICY_PUBLISHED","SystemSetting",setting.getId(),facility,old,p);return p;
    }
    public Optional<OverdueSources.ReminderPolicy> reminder(Rental rental,Instant now){return read(rental.getFacility().getId(),now).filter(s->s.policy().reminderCooldownMinutes()!=null).map(s->new OverdueSources.ReminderPolicy(s.reference(),s.policy().version(),Duration.ofMinutes(s.policy().reminderCooldownMinutes())));}
    public Optional<SupportSources.CloseRule> read(SupportTicket ticket,SupportState state,Instant now){
        if(state==null||state.getResolvedAt()==null)return Optional.empty();
        return read(ticket.getFacility().getId(),now).filter(s->s.policy().supportReviewDays()!=null&&s.policy().customerMayClose()!=null).map(s->new SupportSources.CloseRule(s.reference(),s.policy().version(),s.policy().customerMayClose(),state.getResolvedAt().atZone(ZONE).plusDays(s.policy().supportReviewDays()).toInstant()));
    }
    private Policy decode(SystemSetting setting,UUID facility){try{var p=mapper.readValue(setting.getValue(),Policy.class);if(p==null||!facility.equals(p.facilityId())||p.revision()<1||p.version()==null||p.version().isBlank()||p.publishedBy()==null||p.publishedAt()==null)throw ApiExceptions.conflict("Communication policy metadata incomplete");validate(new Input(p.revision(),p.effectiveFrom(),p.effectiveTo(),p.reminderCooldownMinutes(),p.supportReviewDays(),p.customerMayClose()));return p;}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw ApiExceptions.conflict("Communication policy invalid");}}
    private static void validate(Input p){if(p==null||p.expectedRevision()==null||p.expectedRevision()<0||p.effectiveFrom()==null||p.effectiveTo()!=null&&p.effectiveTo().isBefore(p.effectiveFrom())||p.reminderCooldownMinutes()!=null&&(p.reminderCooldownMinutes()<1||p.reminderCooldownMinutes()>525600)||p.supportReviewDays()!=null&&(p.supportReviewDays()<7||p.supportReviewDays()>365)||((p.supportReviewDays()==null)!=(p.customerMayClose()==null)))throw ApiExceptions.validation("Invalid communication policy; review period must be at least seven calendar days",null);}
}
