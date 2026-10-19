package com.storagehub.service.support;

import com.storagehub.api.support.*;
import com.storagehub.api.support.SupportCommands.*;
import com.storagehub.api.support.SupportCommands.Module;
import com.storagehub.api.support.SupportCommands.Version;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.NotificationService;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.storagehub.service.support.SupportSources.*;

@Service @RequiredArgsConstructor @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class SupportService {
    public enum Audience { CUSTOMER, MANAGER, STAFF }
    private final EntityManager em;
    private final SupportPersistence receipts;
    private final AuditLogService audit;
    private final NotificationService notifications;
    private final ObjectProvider<Clock> clocks;
    private final ObjectProvider<EvidenceSource> evidence;
    private final ObjectProvider<SlaSource> slas;
    private final ObjectProvider<ClosePolicySource> closePolicies;
    private final ObjectProvider<NotificationSource> reviewNotifications;
    private final ObjectProvider<EscalationSource> receivers;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.communication.CommunicationSourceAdapters> communication;

    @Transactional(readOnly=true)
    public PageResponse<SupportResponse> list(ActorPrincipal actor,Audience audience,SupportQuery q,String correlation) {
        authorize(actor,audience,false);
        StringBuilder where=new StringBuilder(" where t.facility is not null");var params=new HashMap<String,Object>();
        if(audience==Audience.CUSTOMER){where.append(" and t.customer.id=:actor");params.put("actor",actor.userId());}
        else {
            var scopes=actor.facilityScopes().entrySet().stream().filter(e->e.getValue()!=null&&e.getValue().includes(audience==Audience.MANAGER?FacilityScopeLevel.READ:FacilityScopeLevel.OPERATE))
                .map(Map.Entry::getKey).filter(id->currentScope(actor.userId(),id,audience==Audience.MANAGER?FacilityScopeLevel.READ:FacilityScopeLevel.OPERATE)).toList();
            if(scopes.isEmpty())return page(List.of(),q,0,correlation);
            where.append(" and t.facility.id in :scopes");params.put("scopes",scopes);
            if(audience==Audience.STAFF){where.append(" and t.assignedTo.id=:actor");params.put("actor",actor.userId());}
        }
        if(q.facilityId()!=null){
            if(audience!=Audience.CUSTOMER)requireScope(actor,q.facilityId(),audience==Audience.STAFF?FacilityScopeLevel.OPERATE:FacilityScopeLevel.READ);
            // Customer visibility is already restricted to owned tickets above; no staff facility grant is required.
            where.append(" and t.facility.id=:facility");params.put("facility",q.facilityId());
        }
        if(q.staffId()!=null){where.append(" and t.assignedTo.id=:staff");params.put("staff",q.staffId());}
        if(q.status()!=null){where.append(" and t.status=:status");params.put("status",q.status());}
        if(!q.search().isEmpty()){where.append(" and (lower(t.subject) like :search escape '!' or lower(t.description) like :search escape '!')");params.put("search","%"+q.search().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        boolean activitySort=q.sort().equals("updatedAt");
        String activityJoin=activitySort?" left join SupportState w on w.id=t.id":"";
        String sortExpression=activitySort?"coalesce(w.changedAt,t.updatedAt)":"t."+q.sort();
        var rows=em.createQuery("select t from SupportTicket t"+activityJoin+where+" order by "+sortExpression+(q.descending()?" desc":" asc")+(q.sort().equals("id")?"":", t.id asc"),SupportTicket.class);
        var count=em.createQuery("select count(t) from SupportTicket t"+where,Long.class);params.forEach((k,v)->{rows.setParameter(k,v);count.setParameter(k,v);});
        return page(rows.setFirstResult(q.page()*q.size()).setMaxResults(q.size()).getResultList().stream().map(this::response).toList(),q,count.getSingleResult(),correlation);
    }
    @Transactional(readOnly=true)
    public SupportResponse detail(ActorPrincipal actor,Audience audience,UUID id){return response(visible(actor,audience,id,false));}

    @Transactional(readOnly=true)
    public SupportStatusSummary summary(ActorPrincipal actor,UUID facilityId) {
        authorize(actor,Audience.MANAGER,false);
        requireScope(actor,facilityId,FacilityScopeLevel.READ);
        var counts=new LinkedHashMap<String,Long>();
        for(var status:SupportTicketStatus.values())counts.put(status.name(),0L);
        var rows=em.createQuery("select t.status,count(t) from SupportTicket t where t.facility.id=:facility group by t.status",Object[].class)
            .setParameter("facility",facilityId).getResultList();
        for(var row:rows)counts.put(((SupportTicketStatus)row[0]).name(),(Long)row[1]);
        long unassigned=em.createQuery("select count(t) from SupportTicket t join SupportState w on w.id=t.id where t.facility.id=:facility and t.status=:open and t.assignedTo is null and w.acceptedBy is null and w.acceptedAt is null",Long.class)
            .setParameter("facility",facilityId).setParameter("open",SupportTicketStatus.open).getSingleResult();
        return new SupportStatusSummary(Map.copyOf(counts),unassigned);
    }

    @Transactional(readOnly=true)
    public PageResponse<SupportQueueItem> queue(ActorPrincipal actor,SupportQuery q,String correlation) {
        authorize(actor,Audience.STAFF,false);
        var scopes=actor.facilityScopes().entrySet().stream()
            .filter(e->e.getValue()!=null&&e.getValue().includes(FacilityScopeLevel.OPERATE))
            .map(Map.Entry::getKey).filter(id->currentScope(actor.userId(),id,FacilityScopeLevel.OPERATE)).toList();
        if(scopes.isEmpty())return page(List.of(),q,0,correlation);
        String where=" where t.status=:open and t.assignedTo is null and t.facility.id in :scopes"
            +" and w.acceptedBy is null and w.acceptedAt is null";
        var params=new HashMap<String,Object>();params.put("open",SupportTicketStatus.open);params.put("scopes",scopes);
        if(q.status()!=null&&q.status()!=SupportTicketStatus.open)return page(List.of(),q,0,correlation);
        if(!q.search().isEmpty()){
            where+=" and lower(t.subject) like :search escape '!'";
            params.put("search","%"+q.search().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        String join=" from SupportTicket t join SupportState w on w.id=t.id";
        String sort=q.sort().equals("updatedAt")?"coalesce(w.changedAt,t.updatedAt)":"t."+q.sort();
        var rows=em.createQuery("select t.id,t.facility.id,t.subject,t.createdAt,w.version"+join+where
            +" order by "+sort+(q.descending()?" desc":" asc")+(q.sort().equals("id")?"":", t.id asc"),Object[].class);
        var count=em.createQuery("select count(t)"+join+where,Long.class);
        params.forEach((k,v)->{rows.setParameter(k,v);count.setParameter(k,v);});
        var items=rows.setFirstResult(q.page()*q.size()).setMaxResults(q.size()).getResultList().stream()
            .map(row->new SupportQueueItem((UUID)row[0],(UUID)row[1],(String)row[2],(Instant)row[3],(Long)row[4])).toList();
        return page(items,q,count.getSingleResult(),correlation);
    }

    public SupportResponse create(ActorPrincipal actor,Create body,String key){return create(actor,body,key,null);}
    private SupportResponse create(ActorPrincipal actor,Create body,String key,UUID parent) {
        authorize(actor,Audience.CUSTOMER,true);lockActor(actor);
        Object payload=Arrays.asList(body,parent);String operation=parent==null?"support_create":"support_follow_up";
        if(parent!=null){var previous=visible(actor,Audience.CUSTOMER,parent,true);if(previous.getStatus()!=SupportTicketStatus.closed)throw ApiExceptions.conflict("Follow-up requires a closed parent ticket");}
        var replay=receipts.replay(actor.userId(),operation,key,payload,SupportResponse.class);if(replay.isPresent())return replay.get();
        UUID requestedFacility=body.facilityId();
        if(parent!=null&&requestedFacility==null)requestedFacility=em.find(SupportTicket.class,parent).getFacility().getId();
        Facility facility=linkedFacility(actor,body.linkedRecord(),requestedFacility);
        if(parent!=null){var previous=em.find(SupportTicket.class,parent);if(!previous.getFacility().getId().equals(facility.getId()))throw ApiExceptions.validation("Follow-up facility must match parent",null);}
        var ticket=new SupportTicket();ticket.setCustomer(em.getReference(User.class,actor.userId()));ticket.setFacility(facility);
        ticket.setSubject(text(body.subject(),200));ticket.setDescription(text(body.description(),4000));em.persist(ticket);em.flush();
        var state=new SupportState(ticket,body.linkedRecord()==null?null:body.linkedRecord().type().name(),body.linkedRecord()==null?null:body.linkedRecord().id(),parent,now());em.persist(state);
        append(actor,ticket,state,"CUSTOMER",Visibility.PUBLIC,body.description(),body.evidenceFileIds());
        event(actor,ticket,state,"CREATED","Created Support ticket");notifySupportStaff(ticket,"New Support ticket",ticket.getSubject());notifyManagers(ticket,"New Support ticket",ticket.getSubject());
        notify(ticket.getCustomer().getId(),ticket,"Đã ghi nhận yêu cầu hỗ trợ của bạn",ticket.getSubject());
        return finish(actor,operation,key,payload,ticket);
    }
    public SupportResponse followUp(ActorPrincipal actor,UUID parent,Create body,String key) {
        // Parent ownership is rechecked before replay; internal messages/files are never cloned.
        return create(actor,body,key,parent);
    }
    @Transactional(readOnly=true)
    public PageResponse<SupportResponse.StaffOption> staffOptions(ActorPrincipal actor,UUID facility,SupportQuery q,String correlation) {
        authorize(actor,Audience.MANAGER,false);requireScope(actor,facility,FacilityScopeLevel.READ);
        // user_facility_scopes is unique(user_id, facility_id); DISTINCT is unnecessary and can
        // make ORDER BY a foreign-key expression invalid on H2/MySQL-compatible dialects.
        var users=em.createQuery("select s.user from UserFacilityScope s where s.facility.id=:facility and s.scopeLevel in :levels and s.user.status=:active order by s.user.fullName,s.user.id",User.class)
            .setParameter("facility",facility).setParameter("levels",List.of(FacilityScopeLevel.OPERATE,FacilityScopeLevel.MANAGE)).setParameter("active",UserStatus.ACTIVE).getResultList().stream()
            .filter(u->hasRole(u,RoleCode.STAFF)&&hasPermission(u,SystemPermission.MANAGE_SUPPORT)&&hasPermission(u,SystemPermission.VIEW_SUPPORT)).map(u->new SupportResponse.StaffOption(u.getId(),u.getFullName())).toList();
        int from=Math.min(q.page()*q.size(),users.size());return page(users.subList(from,Math.min(from+q.size(),users.size())),q,users.size(),correlation);
    }
    public SupportResponse assign(ActorPrincipal actor,UUID id,Assignment body,String key) {
        throw ApiExceptions.forbidden("Manager Support is read-only; Staff must claim requests directly");
    }
    public SupportResponse accept(ActorPrincipal actor,UUID id,Version body,String key) {
        var ticket=command(actor,Audience.STAFF,id);Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_accept",key,payload,SupportResponse.class);if(replay.isPresent())return replay.get();
        var state=requiredState(ticket,body.expectedVersion());requireStatus(ticket,SupportTicketStatus.open);
        state.accept(actor.userId(),now());ticket.setStatus(SupportTicketStatus.in_progress);event(actor,ticket,state,"ACCEPTED","Staff accepted current assignment");
        notify(ticket.getCustomer().getId(),ticket,"Support ticket received",ticket.getSubject());
        notifyManagers(ticket,"Staff accepted Support",ticket.getSubject());return finish(actor,"support_accept",key,payload,ticket);
    }
    public SupportResponse claim(ActorPrincipal actor,UUID id,Version body,String key) {
        var staff=authorize(actor,Audience.STAFF,true);lockActor(actor);
        var ticket=em.find(SupportTicket.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(ticket==null||ticket.getFacility()==null)throw ApiExceptions.notFound("Support ticket not found");
        em.refresh(ticket,LockModeType.PESSIMISTIC_WRITE);
        requireScope(actor,ticket.getFacility().getId(),FacilityScopeLevel.OPERATE);
        if(!eligible(staff,ticket.getFacility().getId()))throw ApiExceptions.forbidden("Current Staff is not eligible for Support at this facility");
        Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_claim",key,payload,SupportResponse.class);
        if(replay.isPresent())return replay.get();
        var state=requiredState(ticket,body.expectedVersion());
        if(ticket.getStatus()!=SupportTicketStatus.open||ticket.getAssignedTo()!=null
            ||state.getAcceptedBy()!=null||state.getAcceptedAt()!=null)
            throw ApiExceptions.conflict("Support ticket is already claimed or unavailable");
        var at=now();ticket.setAssignedTo(staff);state.assign(at);
        event(actor,ticket,state,"ASSIGNED","Staff claimed unassigned ticket");
        state.accept(actor.userId(),at);ticket.setStatus(SupportTicketStatus.in_progress);
        event(actor,ticket,state,"ACCEPTED","Claiming Staff accepted ticket");
        notify(ticket.getCustomer().getId(),ticket,"Support ticket received",ticket.getSubject());
        notifyManagers(ticket,"Staff claimed Support",ticket.getSubject());
        return finish(actor,"support_claim",key,payload,ticket);
    }
    public SupportResponse.Message customerMessage(ActorPrincipal actor,UUID id,CustomerMessage body,String key) {
        var ticket=command(actor,Audience.CUSTOMER,id);Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_customer_message",key,payload,SupportResponse.Message.class);if(replay.isPresent())return replayMessage(actor,ticket,replay.get());
        if(ticket.getStatus()==SupportTicketStatus.closed)throw ApiExceptions.conflict("Closed ticket only accepts a linked follow-up");
        var state=state(ticket,null);
        if(ticket.getStatus()==SupportTicketStatus.waiting_customer){version(state,body.expectedVersion());requireAccepted(ticket,state);ticket.setStatus(SupportTicketStatus.in_progress);state.touch(now());}
        // A public reply to resolved is not an implicit reopen.
        var message=append(actor,ticket,state,"CUSTOMER",Visibility.PUBLIC,body.body(),body.evidenceFileIds());
        notifyAssignedStaff(ticket,"Customer replied to Support",ticket.getSubject());
        notifyManagers(ticket,"Customer replied to Support",ticket.getSubject());
        return finishMessage(actor,"support_customer_message",key,payload,ticket,message);
    }
    public SupportResponse.Message staffMessage(ActorPrincipal actor,UUID id,StaffMessage body,String key) {
        var ticket=command(actor,Audience.STAFF,id);Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_staff_message",key,payload,SupportResponse.Message.class);if(replay.isPresent())return replayMessage(actor,ticket,replay.get());
        nonterminal(ticket);var state=state(ticket,null);requireAccepted(ticket,state);
        if(body.visibility()==null)throw ApiExceptions.validation("Explicit visibility required",null);
        var message=append(actor,ticket,state,"STAFF",body.visibility(),body.body(),body.evidenceFileIds());
        if(body.visibility()==Visibility.PUBLIC)notify(ticket.getCustomer().getId(),ticket,"Staff replied to Support",ticket.getSubject());
        notifyManagers(ticket,body.visibility()==Visibility.PUBLIC?"Staff replied to Support":"Tin nhắn mới từ nhân viên về yêu cầu hỗ trợ",ticket.getSubject());
        return finishMessage(actor,"support_staff_message",key,payload,ticket,message);
    }
    public SupportResponse.Message managerMessage(ActorPrincipal actor,UUID id,StaffMessage body,String key) {
        var user=authorize(actor,Audience.MANAGER,false);
        if(!actor.hasPermission(SystemPermission.MANAGE_SUPPORT)||!hasPermission(user,SystemPermission.MANAGE_SUPPORT))throw ApiExceptions.forbidden("Support update permission required");
        lockActor(actor);var ticket=visible(actor,Audience.MANAGER,id,false);
        requireScope(actor,ticket.getFacility().getId(),FacilityScopeLevel.MANAGE);
        em.lock(ticket,LockModeType.PESSIMISTIC_WRITE);em.refresh(ticket,LockModeType.PESSIMISTIC_WRITE);
        visible(actor,Audience.MANAGER,id,false);
        requireScope(actor,ticket.getFacility().getId(),FacilityScopeLevel.MANAGE);
        if(body.visibility()!=Visibility.INTERNAL)throw ApiExceptions.forbidden("Manager may only send internal Support messages");
        Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_manager_message",key,payload,SupportResponse.Message.class);
        if(replay.isPresent())return replayMessage(actor,ticket,replay.get());
        nonterminal(ticket);var state=state(ticket,null);requireAccepted(ticket,state);
        var message=append(actor,ticket,state,"MANAGER",Visibility.INTERNAL,body.body(),body.evidenceFileIds());
        notify(ticket.getAssignedTo().getId(),ticket,"Manager replied to Support",ticket.getSubject());
        return finishMessage(actor,"support_manager_message",key,payload,ticket,message);
    }
    public SupportResponse requestInformation(ActorPrincipal actor,UUID id,Information body,String key) {
        var ticket=command(actor,Audience.STAFF,id);Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_request_info",key,payload,SupportResponse.class);if(replay.isPresent())return replay.get();
        var state=requiredState(ticket,body.expectedVersion());requireStatus(ticket,SupportTicketStatus.in_progress);requireAccepted(ticket,state);
        append(actor,ticket,state,"STAFF",Visibility.PUBLIC,body.message(),body.evidenceFileIds());ticket.setStatus(SupportTicketStatus.waiting_customer);
        event(actor,ticket,state,"REQUEST_INFORMATION","Customer information requested");notify(ticket.getCustomer().getId(),ticket,"Support needs your information",ticket.getSubject());notifyManagers(ticket,"Support needs customer information",ticket.getSubject());
        return finish(actor,"support_request_info",key,payload,ticket);
    }
    public SupportResponse resolve(ActorPrincipal actor,UUID id,Resolution body,String key) {
        var ticket=command(actor,Audience.STAFF,id);Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_resolve",key,payload,SupportResponse.class);if(replay.isPresent())return replay.get();
        var state=requiredState(ticket,body.expectedVersion());requireStatus(ticket,SupportTicketStatus.in_progress);requireAccepted(ticket,state);
        requireEscalationsResolved(ticket);
        // The linked record supplies context. Resolution records Staff's Support outcome
        // for Customer review; it does not complete or mutate the linked module.
        var evidenceFiles=files(body.evidenceFileIds());
        String summary=body.summary()==null?"":body.summary().trim();
        if(summary.isEmpty()&&evidenceFiles.isEmpty())throw ApiExceptions.validation("Cần nhập mô tả xử lý hoặc đính kèm minh chứng để gửi cho khách hàng.",null);
        if(summary.isEmpty())summary="Nhân viên đã gửi minh chứng xử lý. Vui lòng kiểm tra và xác nhận kết quả.";
        append(actor,ticket,state,"STAFF",Visibility.PUBLIC,summary,evidenceFiles);ticket.setStatus(SupportTicketStatus.resolved);state.resolve(actor.userId(),now());
        var resolvedEvent=event(actor,ticket,state,"RESOLVED","Staff supplied resolution");em.flush();
        var channel=communication==null?null:communication.getIfAvailable();
        if(channel==null||!channel.enqueueReview(ticket,state,resolvedEvent,now()))notify(ticket.getCustomer().getId(),ticket,"Support resolved; please review",ticket.getSubject());
        notifyManagers(ticket,"Support resolved",ticket.getSubject());
        return finish(actor,"support_resolve",key,payload,ticket);
    }
    public SupportResponse close(ActorPrincipal actor,UUID id,Close body,String key) {
        var ticket=command(actor,Audience.CUSTOMER,id);Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_close",key,payload,SupportResponse.class);if(replay.isPresent())return replay.get();
        var state=requiredState(ticket,body.expectedVersion());requireStatus(ticket,SupportTicketStatus.resolved);requireEscalationsResolved(ticket);
        if(!closeRule(ticket,state).customerMayClose())throw ApiExceptions.conflict("Shared policy does not allow Customer close");
        if(body.feedback()!=null&&!body.feedback().isBlank())append(actor,ticket,state,"CUSTOMER",Visibility.PUBLIC,body.feedback(),List.of());
        ticket.setStatus(SupportTicketStatus.closed);state.close(now());event(actor,ticket,state,"CLOSED","Customer confirmed resolution");notifyManagers(ticket,"Customer closed Support",ticket.getSubject());
        notifyAssignedStaff(ticket,"Customer closed Support",ticket.getSubject());
        notify(ticket.getCustomer().getId(),ticket,"Yêu cầu hỗ trợ của bạn đã được đóng",ticket.getSubject());
        return finish(actor,"support_close",key,payload,ticket);
    }
    public SupportResponse reopen(ActorPrincipal actor,UUID id,Reopen body,String key) {
        var ticket=command(actor,Audience.CUSTOMER,id);Object payload=payload(id,body);
        var replay=receipts.replay(actor.userId(),"support_reopen",key,payload,SupportResponse.class);if(replay.isPresent())return replay.get();
        var state=requiredState(ticket,body.expectedVersion());requireStatus(ticket,SupportTicketStatus.resolved);
        boolean keep=eligible(ticket.getAssignedTo(),ticket.getFacility().getId())&&state.getAcceptedBy()!=null&&state.getAcceptedBy().equals(ticket.getAssignedTo().getId());
        if(!keep)ticket.setAssignedTo(null);ticket.setStatus(keep?SupportTicketStatus.in_progress:SupportTicketStatus.open);state.reopen(keep,now());
        append(actor,ticket,state,"CUSTOMER",Visibility.PUBLIC,body.reason(),List.of());event(actor,ticket,state,"REOPENED",text(body.reason(),2000));
        notifyManagers(ticket,"Customer reopened Support",ticket.getSubject());
        if(keep)notifyAssignedStaff(ticket,"Customer reopened Support",ticket.getSubject());
        else notifySupportStaff(ticket,"Có yêu cầu hỗ trợ cần tiếp nhận lại",ticket.getSubject());
        notify(ticket.getCustomer().getId(),ticket,"Đã ghi nhận yêu cầu xử lý lại của bạn",ticket.getSubject());
        return finish(actor,"support_reopen",key,payload,ticket);
    }
    public SupportResponse.Escalation escalate(ActorPrincipal actor,UUID id,Escalate body,String key) {
        throw ApiExceptions.forbidden("Support now supports direct Customer and Staff interaction; new module escalations are disabled");
    }
    public SupportResponse.Escalation decideEscalation(ActorPrincipal actor,UUID id,UUID escalationId,EscalationDecision body,String key) {
        throw ApiExceptions.forbidden("Manager Support is read-only; Staff must claim requests directly");
    }
    @Transactional(readOnly=true)
    public PageResponse<SupportResponse.Message> messages(ActorPrincipal actor,Audience audience,UUID id,SupportQuery q,String correlation) {
        return messages(actor,audience,id,q,correlation,null);
    }
    @Transactional(readOnly=true)
    public PageResponse<SupportResponse.Message> messages(ActorPrincipal actor,Audience audience,UUID id,SupportQuery q,String correlation,Visibility visibility) {
        if(audience==Audience.CUSTOMER&&visibility==Visibility.INTERNAL)throw ApiExceptions.forbidden("Internal conversation is private");
        var ticket=visible(actor,audience,id,false);var channel=audience==Audience.CUSTOMER?Visibility.PUBLIC:visibility;
        String clause=" where m.ticket.id=:id"+(channel==null?"":" and m.visibility='"+channel.name()+"'");
        var rows=em.createQuery("select m from SupportMessage m"+clause+" order by m.sentAt,m.id",SupportMessage.class).setParameter("id",id).setFirstResult(q.page()*q.size()).setMaxResults(q.size()).getResultList().stream().map(m->messageResponse(actor,ticket,m)).toList();
        long total=em.createQuery("select count(m) from SupportMessage m"+clause,Long.class).setParameter("id",id).getSingleResult();return page(rows,q,total,correlation);
    }
    @Transactional(readOnly=true)
    public PageResponse<SupportResponse.Event> events(ActorPrincipal actor,Audience audience,UUID id,SupportQuery q,String correlation) {
        if(audience==Audience.CUSTOMER)throw ApiExceptions.forbidden("Internal workflow history is not a Customer timeline");visible(actor,audience,id,false);
        var rows=em.createQuery("select e from SupportEvent e where e.ticket.id=:id order by e.recordedAt,e.id",SupportEvent.class).setParameter("id",id).setFirstResult(q.page()*q.size()).setMaxResults(q.size()).getResultList().stream().map(e->new SupportResponse.Event(e.getId(),e.getType(),e.getActorId(),e.getAssignedStaffId(),e.getAssignmentRevision(),e.getReason(),e.getRecordedAt())).toList();
        long total=em.createQuery("select count(e) from SupportEvent e where e.ticket.id=:id",Long.class).setParameter("id",id).getSingleResult();return page(rows,q,total,correlation);
    }
    @Transactional(readOnly=true)
    public PageResponse<SupportResponse.Escalation> escalations(ActorPrincipal actor,Audience audience,UUID id,SupportQuery q,String correlation) {
        if(audience==Audience.CUSTOMER)throw ApiExceptions.forbidden("Internal escalations are not a Customer timeline");visible(actor,audience,id,false);
        var rows=em.createQuery("select e from SupportEscalation e where e.ticket.id=:id order by e.requestedAt,e.id",SupportEscalation.class).setParameter("id",id).setFirstResult(q.page()*q.size()).setMaxResults(q.size()).getResultList().stream().map(e->escalationResponse(e,receivers.getIfAvailable())).toList();
        long total=em.createQuery("select count(e) from SupportEscalation e where e.ticket.id=:id",Long.class).setParameter("id",id).getSingleResult();return page(rows,q,total,correlation);
    }
    /** System-only service entry. No public HTTP route and no implicit scheduler enablement. */
    public boolean autoClose(UUID id) {
        var ticket=em.find(SupportTicket.class,id,LockModeType.PESSIMISTIC_WRITE);if(ticket==null||ticket.getFacility()==null||ticket.getStatus()!=SupportTicketStatus.resolved)return false;
        var state=em.find(SupportState.class,id,LockModeType.PESSIMISTIC_WRITE);if(state==null)return false;
        var rule=closeRule(ticket,state);if(rule.autoCloseAt()==null||now().isBefore(rule.autoCloseAt()))return false;requireEscalationsResolved(ticket);
        requireReviewNotice(ticket,state,rule);
        notify(ticket.getCustomer().getId(),ticket,"Support automatically closed after review period",ticket.getSubject());
        notifyAssignedStaff(ticket,"Yêu cầu hỗ trợ đã tự động đóng",ticket.getSubject());
        notifyManagers(ticket,"Yêu cầu hỗ trợ đã tự động đóng",ticket.getSubject());
        ticket.setStatus(SupportTicketStatus.closed);state.close(now());em.persist(new SupportEvent(ticket,"AUTO_CLOSED",null,state.getAssignmentRevision(),"Shared close policy elapsed",now()));em.flush();
        audit.recordMutation((User)null,"support_auto_close","SupportTicket",id,ticket.getFacility().getId(),null,Map.of("status","closed","policyRef",rule.policyRef(),"policyVersion",rule.policyVersion()));return true;
    }

    private User authorize(ActorPrincipal actor,Audience audience,boolean write) {
        if(write&&actor!=null&&actor.hasRole(RoleCode.MANAGER))throw ApiExceptions.forbidden("Manager Support is read-only");
        if(audience==Audience.MANAGER&&write)throw ApiExceptions.forbidden("Manager Support is read-only");
        if(actor==null)throw ApiExceptions.unauthorized("Authentication required");var user=em.find(User.class,actor.userId());
        if(user==null||user.getStatus()!=UserStatus.ACTIVE)throw ApiExceptions.forbidden("Actor is not active");
        RoleCode role=RoleCode.valueOf(audience.name());if(!actor.hasRole(role)||!hasRole(user,role))throw ApiExceptions.forbidden("Support actor role required");
        if(audience!=Audience.CUSTOMER){var permission=write?SystemPermission.MANAGE_SUPPORT:SystemPermission.VIEW_SUPPORT;
            if(!actor.hasPermission(permission)||!hasPermission(user,permission))throw ApiExceptions.forbidden("Current Support permission required");}
        if(audience==Audience.STAFF&&(!actor.hasPermission(SystemPermission.MANAGE_SUPPORT)||!hasPermission(user,SystemPermission.MANAGE_SUPPORT)))throw ApiExceptions.forbidden("Staff Support operation permission not granted");
        return user;
    }
    private SupportTicket visible(ActorPrincipal actor,Audience audience,UUID id,boolean write) {
        authorize(actor,audience,write);var ticket=em.find(SupportTicket.class,id);if(ticket==null||ticket.getFacility()==null)throw ApiExceptions.notFound("Support ticket not found or needs authorized legacy triage");
        if(audience==Audience.CUSTOMER){if(!ticket.getCustomer().getId().equals(actor.userId()))throw ApiExceptions.notFound("Support ticket not found");}
        else {
            var needed=audience==Audience.MANAGER?(write?FacilityScopeLevel.MANAGE:FacilityScopeLevel.READ):FacilityScopeLevel.OPERATE;
            var level=actor.facilityScopes().get(ticket.getFacility().getId());if(level==null||!level.includes(needed)||!currentScope(actor.userId(),ticket.getFacility().getId(),needed))throw ApiExceptions.notFound("Support ticket not found");
            if(audience==Audience.STAFF&&(ticket.getAssignedTo()==null||!ticket.getAssignedTo().getId().equals(actor.userId())||!eligible(ticket.getAssignedTo(),ticket.getFacility().getId())))throw ApiExceptions.notFound("Support ticket not found");
        }
        return ticket;
    }
    private SupportTicket command(ActorPrincipal actor,Audience audience,UUID id) {
        authorize(actor,audience,true);lockActor(actor);visible(actor,audience,id,true);
        var ticket=em.find(SupportTicket.class,id,LockModeType.PESSIMISTIC_WRITE);em.refresh(ticket,LockModeType.PESSIMISTIC_WRITE);return visible(actor,audience,id,true);
    }
    private void lockActor(ActorPrincipal actor){var u=em.find(User.class,actor.userId(),LockModeType.PESSIMISTIC_WRITE);em.refresh(u,LockModeType.PESSIMISTIC_WRITE);}
    private SupportState state(SupportTicket ticket,Long expected){var state=em.find(SupportState.class,ticket.getId(),LockModeType.PESSIMISTIC_WRITE);if(state==null)throw deferred("Legacy ticket has no verified workflow metadata; no automatic backfill");em.refresh(state,LockModeType.PESSIMISTIC_WRITE);if(expected!=null)version(state,expected);return state;}
    private SupportState requiredState(SupportTicket ticket,Long expected){var state=state(ticket,null);version(state,expected);return state;}
    private void version(SupportState state,Long expected){if(expected==null||expected<0)throw ApiExceptions.validation("expectedVersion required for this transition",null);if(state.getVersion()!=expected)throw ApiExceptions.conflict("Support version is stale");}
    private void requireStatus(SupportTicket ticket,SupportTicketStatus required){if(ticket.getStatus()!=required)throw ApiExceptions.conflict("Support state does not allow this operation");}
    private void nonterminal(SupportTicket ticket){if(Set.of(SupportTicketStatus.resolved,SupportTicketStatus.closed).contains(ticket.getStatus()))throw ApiExceptions.conflict("Terminal Support ticket requires explicit reopen/follow-up");}
    private void requireAccepted(SupportTicket ticket,SupportState state){if(ticket.getAssignedTo()==null||state.getAcceptedAt()==null||!ticket.getAssignedTo().getId().equals(state.getAcceptedBy())||!eligible(ticket.getAssignedTo(),ticket.getFacility().getId()))throw ApiExceptions.conflict("Current eligible Staff assignment has not been accepted");}
    private boolean eligible(User user,UUID facility){if(user==null||user.getStatus()!=UserStatus.ACTIVE||!hasRole(user,RoleCode.STAFF)||!hasPermission(user,SystemPermission.VIEW_SUPPORT)||!hasPermission(user,SystemPermission.MANAGE_SUPPORT))return false;
        return em.createQuery("select count(s) from UserFacilityScope s where s.user.id=:user and s.facility.id=:facility and s.scopeLevel in :levels",Long.class).setParameter("user",user.getId()).setParameter("facility",facility).setParameter("levels",List.of(FacilityScopeLevel.OPERATE,FacilityScopeLevel.MANAGE)).getSingleResult()>0;}
    private boolean hasRole(User user,RoleCode role){return user.getRoles().stream().anyMatch(r->r.getCode()==role);}
    private boolean hasPermission(User user,SystemPermission permission){return user.getRoles().stream().flatMap(r->r.getPermissions().stream()).anyMatch(p->p.getCode().equals(permission.code()));}
    private boolean currentScope(UUID actor,UUID facility,FacilityScopeLevel required){return em.createQuery("select s.scopeLevel from UserFacilityScope s where s.user.id=:user and s.facility.id=:facility",FacilityScopeLevel.class).setParameter("user",actor).setParameter("facility",facility).getResultList().stream().anyMatch(level->level.includes(required));}
    private void requireScope(ActorPrincipal actor,UUID facility,FacilityScopeLevel required){var level=actor.facilityScopes().get(facility);if(level==null||!level.includes(required)||!currentScope(actor.userId(),facility,required))throw ApiExceptions.forbidden("Support facility scope required");}
    private Facility linkedFacility(ActorPrincipal actor,Link link,UUID requested) {
        Facility facility=null;
        if(link!=null){if(link.type()==null||link.id()==null)throw ApiExceptions.validation("Linked record type/id required",null);
            switch(link.type()) {
                case RENTAL -> {var r=em.find(Rental.class,link.id());if(r!=null&&r.getCustomer().getId().equals(actor.userId()))facility=r.getFacility();}
                case RESERVATION -> {var r=em.find(Reservation.class,link.id());if(r!=null&&r.getCustomer().getId().equals(actor.userId()))facility=r.getFacility();}
                case PAYMENT -> {var p=em.find(Payment.class,link.id());if(p!=null&&p.getReservation()!=null&&p.getReservation().getCustomer().getId().equals(actor.userId()))facility=p.getReservation().getFacility();else if(p!=null&&p.getRenewal()!=null&&p.getRenewal().getRental().getCustomer().getId().equals(actor.userId()))facility=p.getRenewal().getRental().getFacility();}
                case STORAGE_UNIT -> {var rows=em.createQuery("select r.facility from Rental r where r.customer.id=:customer and r.storageUnit.id=:unit",Facility.class).setParameter("customer",actor.userId()).setParameter("unit",link.id()).setMaxResults(1).getResultList();if(!rows.isEmpty())facility=rows.getFirst();}
            }
            if(facility==null)throw ApiExceptions.notFound("Owned linked record not found");if(requested!=null&&!facility.getId().equals(requested))throw ApiExceptions.validation("Facility conflicts with linked record",null);
        } else {if(requested==null)throw ApiExceptions.validation("Facility required when no owned linked record is supplied",null);facility=em.find(Facility.class,requested);if(facility==null)throw ApiExceptions.notFound("Facility not found");}
        if(facility.getStatus()!=FacilityStatus.active)throw ApiExceptions.conflict("Support facility is not active");return facility;
    }
    private SupportMessage append(ActorPrincipal actor,SupportTicket ticket,SupportState state,String role,Visibility visibility,String body,List<UUID> requested) {
        var files=files(requested);attach(actor,ticket,visibility,files);var message=new SupportMessage(ticket,actor.userId(),role,visibility.name(),text(body,4000),receipts.json(files),now());em.persist(message);
        if(role.equals("STAFF")&&visibility==Visibility.PUBLIC)state.reply(now());
        else state.touch(now());
        return message;
    }
    private List<UUID> files(List<UUID> requested){if(requested==null)return List.of();if(requested.size()>10||requested.stream().anyMatch(Objects::isNull)||new HashSet<>(requested).size()!=requested.size())throw ApiExceptions.validation("Use at most 10 distinct evidence file IDs",null);return List.copyOf(requested);}
    private void attach(ActorPrincipal actor,SupportTicket ticket,Visibility visibility,List<UUID> files){if(files.isEmpty())return;var source=evidence.getIfAvailable();if(source==null||!source.atomic())throw deferred("Shared Support file attach/read authorization unavailable");source.requireAttach(actor,ticket,visibility,files);}
    private SupportResponse.Message messageResponse(ActorPrincipal actor,SupportTicket ticket,SupportMessage message){var files=receipts.files(message.getEvidenceJson());var source=evidence.getIfAvailable();boolean complete=files.isEmpty()||source!=null&&source.supportsFiles(files);if(!files.isEmpty()&&complete)source.requireRead(actor,ticket,Visibility.valueOf(message.getVisibility()),files);
        return new SupportResponse.Message(message.getId(),ticket.getId(),message.getAuthorId(),message.getAuthorRole(),message.getVisibility(),message.getBody(),message.getSentAt(),complete?"COMPLETE":"UNKNOWN",complete?files:null);}
    private SupportResponse.Message replayMessage(ActorPrincipal actor,SupportTicket ticket,SupportResponse.Message cached){
        var message=em.find(SupportMessage.class,cached.id());
        if(message==null||!message.getTicket().getId().equals(ticket.getId()))throw ApiExceptions.conflict("Stored Support message reference is invalid");
        // File authorization may have changed since the original command, even on an idempotent retry.
        return messageResponse(actor,ticket,message);
    }
    private SupportResponse response(SupportTicket ticket) {
        var state=em.find(SupportState.class,ticket.getId());var source=slas.getIfAvailable();var sla=source==null||state==null?null:source.read(ticket,state,now()).orElse(null);
        if(sla!=null&&(sla.priority()==null||!Set.of("HIGH","MEDIUM","LOW").contains(sla.priority())||sla.policyRef()==null||sla.policyRef().isBlank()||sla.policyVersion()==null||sla.policyVersion().isBlank()))throw ApiExceptions.conflict("Authoritative Support SLA inconsistent");
        if(sla!=null&&sla.activeWorkPaused()!=(ticket.getStatus()==SupportTicketStatus.waiting_customer))throw ApiExceptions.conflict("SLA processing pause must reflect waiting_customer only");
        return new SupportResponse(ticket.getId(),ticket.getCustomer().getId(),ticket.getFacility()==null?null:ticket.getFacility().getId(),ticket.getAssignedTo()==null?null:ticket.getAssignedTo().getId(),ticket.getStatus(),ticket.getSubject(),ticket.getDescription(),ticket.getCreatedAt(),state==null?null:state.getVersion(),state==null?null:state.getAssignmentRevision(),state==null?null:state.getAssignedAt(),state==null?null:state.getAcceptedAt(),state==null?null:state.getResolvedAt(),state==null?null:state.getClosedAt(),state==null?null:state.getResolvedBy(),state==null?null:state.getParentTicketId(),state==null?null:state.getLinkedType(),state==null?null:state.getLinkedId(),state!=null,sla==null?"UNKNOWN":"COMPLETE",sla,sla==null?"DEFERRED_SOURCE: shared Support priority/SLA/calendar":null,ticket.getAssignedTo()==null?null:ticket.getAssignedTo().getFullName(),ticket.getCustomer().getFullName());
    }
    private EscalationSource receiver(Module module){var source=receivers.getIfAvailable();if(source==null||!source.atomic()||!source.supports(module))throw deferred("Authorized escalation receiving/result adapter unavailable");return source;}
    private ReceiverResult result(SupportEscalation e,EscalationSource source){if(e.getReceiverRef()==null||source==null)return null;var r=source.result(e.getTicket(),e).orElse(null);if(r!=null&&(!e.getTicket().getId().equals(r.ticketId())||!e.getId().equals(r.escalationId())||!e.getReceiverRef().equals(r.receiverRef())||!Set.of("ACKNOWLEDGED","REJECTED","COMPLETED").contains(r.status())||r.status().equals("COMPLETED")&&r.resultRef()==null))throw ApiExceptions.conflict("Escalation result reference/state inconsistent");return r;}
    private SupportResponse.Escalation escalationResponse(SupportEscalation e,EscalationSource source){var r=result(e,source);return new SupportResponse.Escalation(e.getId(),e.getTicket().getId(),e.getTargetModule(),e.getStatus(),e.getReason(),e.getDecisionReason(),e.getReceiverRef(),r==null?null:r.status(),r==null?null:r.resultRef(),e.getStatus().equals("REJECTED")||r!=null?"COMPLETE":"UNKNOWN",e.getRequestedAt(),e.getDecidedAt());}
    private void requireEscalationsResolved(SupportTicket ticket){for(var e:em.createQuery("select e from SupportEscalation e where e.ticket.id=:id and e.status<>'REJECTED'",SupportEscalation.class).setParameter("id",ticket.getId()).getResultList()){var r=result(e,receivers.getIfAvailable());if(r==null)throw deferred("Trusted escalation result unavailable");if(!Set.of("COMPLETED","REJECTED").contains(r.status()))throw ApiExceptions.conflict("Escalation is still unresolved");}}
    private CloseRule closeRule(SupportTicket ticket,SupportState state){var source=closePolicies.getIfAvailable();var rule=source==null?null:source.read(ticket,state,now()).orElse(null);if(rule==null)throw deferred("Shared Support close policy unavailable");if(rule.policyRef()==null||rule.policyRef().isBlank()||rule.policyVersion()==null||rule.policyVersion().isBlank()||rule.autoCloseAt()!=null&&(state.getResolvedAt()==null||rule.autoCloseAt().isBefore(state.getResolvedAt().plus(Duration.ofDays(7)))))throw ApiExceptions.conflict("Support close policy inconsistent with approved review period");return rule;}
    private void requireReviewNotice(SupportTicket ticket,SupportState state,CloseRule rule) {
        var source=reviewNotifications.getIfAvailable();
        if(source==null||!source.consistentThroughClose())throw deferred("Verified Support review notification delivery unavailable");
        var events=em.createQuery("select e from SupportEvent e where e.ticket.id=:ticket and e.type='RESOLVED' and e.recordedAt=:resolvedAt and e.actorId=:staff and e.assignmentRevision=:revision",SupportEvent.class)
            .setParameter("ticket",ticket.getId()).setParameter("resolvedAt",state.getResolvedAt())
            .setParameter("staff",state.getResolvedBy()).setParameter("revision",state.getAssignmentRevision()).setMaxResults(2).getResultList();
        if(events.size()!=1)throw deferred("Current Support resolution notice identity unavailable or ambiguous");
        UUID resolutionEvent=events.getFirst().getId();Instant checkedAt=now();
        var proof=source.reviewNotice(ticket,resolutionEvent,rule,checkedAt).orElse(null);
        if(proof==null)throw deferred("Verified Support review notification delivery unavailable");
        if(proof.reference()==null||!ticket.getId().equals(proof.ticketId())||!ticket.getCustomer().getId().equals(proof.customerId())
            ||!resolutionEvent.equals(proof.resolutionEventId())||!rule.policyRef().equals(proof.policyRef())||!rule.policyVersion().equals(proof.policyVersion())
            ||proof.deliveredAt()==null||proof.deliveredAt().isBefore(state.getResolvedAt())||proof.deliveredAt().isAfter(checkedAt))
            throw ApiExceptions.conflict("Support review notification proof is inconsistent");
    }
    private SupportEvent event(ActorPrincipal actor,SupportTicket ticket,SupportState state,String type,String reason){var at=type.equals("RESOLVED")?state.getResolvedAt():now();if(at==null)throw ApiExceptions.conflict("Resolution time missing");state.touch(at);var event=new SupportEvent(ticket,type,actor.userId(),state.getAssignmentRevision(),text(reason,2000),at);em.persist(event);return event;}
    private SupportResponse finish(ActorPrincipal actor,String operation,String key,Object payload,SupportTicket ticket){em.flush();var result=response(ticket);receipts.remember(actor.userId(),operation,key,payload,result);audit(actor,ticket,operation);return result;}
    private SupportResponse.Message finishMessage(ActorPrincipal actor,String operation,String key,Object payload,SupportTicket ticket,SupportMessage message){em.flush();var result=messageResponse(actor,ticket,message);receipts.remember(actor.userId(),operation,key,payload,result);audit(actor,ticket,operation);return result;}
    private void audit(ActorPrincipal actor,SupportTicket ticket,String operation){audit.recordMutation(em.getReference(User.class,actor.userId()),operation,"SupportTicket",ticket.getId(),ticket.getFacility().getId(),null,Map.of("status",ticket.getStatus().name()));}
    private void notify(UUID recipient,SupportTicket ticket,String title,String content){notifications.createNotification(recipient,NotificationType.SUPPORT,SupportNotificationText.title(title),"Yêu cầu hỗ trợ: "+content,ticket.getId());}
    private void notifyAssignedStaff(SupportTicket ticket,String title,String content){if(eligible(ticket.getAssignedTo(),ticket.getFacility().getId()))notify(ticket.getAssignedTo().getId(),ticket,title,content);}
    private void notifySupportStaff(SupportTicket ticket,String title,String content){
        var staff=em.createQuery("select distinct s.user from UserFacilityScope s where s.facility.id=:facility and s.scopeLevel=:level and s.user.status=:active",User.class)
            .setParameter("facility",ticket.getFacility().getId()).setParameter("level",FacilityScopeLevel.OPERATE).setParameter("active",UserStatus.ACTIVE).getResultList();
        for(var user:staff)if(eligible(user,ticket.getFacility().getId()))notify(user.getId(),ticket,title,content);
    }
    private void notifyManagers(SupportTicket ticket,String title,String content){var managers=em.createQuery("select distinct s.user from UserFacilityScope s where s.facility.id=:facility and s.scopeLevel=:level and s.user.status=:active",User.class).setParameter("facility",ticket.getFacility().getId()).setParameter("level",FacilityScopeLevel.MANAGE).setParameter("active",UserStatus.ACTIVE).getResultList();for(var u:managers)if(hasRole(u,RoleCode.MANAGER)&&hasPermission(u,SystemPermission.VIEW_SUPPORT))notify(u.getId(),ticket,title,content);}
    private <T> PageResponse<T> page(List<T> rows,SupportQuery q,long total,String correlation){return new PageResponse<>(List.copyOf(rows),new PageResponse.Pagination(q.page(),q.size(),total,(int)((total+q.size()-1)/q.size()),q.sort()+","+(q.descending()?"desc":"asc")),correlation);}
    private Object payload(UUID id,Object body){return Arrays.asList(id,body);}
    private String text(String value,int limit){if(value==null||value.isBlank()||value.length()>limit)throw ApiExceptions.validation("Text must contain 1–"+limit+" characters",null);return value.trim();}
    private Instant now(){var clock=clocks.getIfAvailable();return (clock==null?Clock.systemUTC():clock).instant();}
    private static RuntimeException deferred(String reason){return ApiExceptions.conflict("DEFERRED_SOURCE: "+reason);}
}
