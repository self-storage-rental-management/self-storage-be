package com.storagehub.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.support.CustomerChatSessionController.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.model.SupportTicketStatus;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class CustomerChatSessionService {
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final SupportService support;
    private final ObjectProvider<Clock> clocks;
    private Instant now(){var clock=clocks.getIfAvailable();return clock==null?Instant.now():clock.instant();}
    private void authorize(ActorPrincipal actor) {
        if(!actor.hasRole(RoleCode.CUSTOMER)||em.createQuery("select count(u) from User u join u.roles r where u.id=:id and u.status=:active and r.code=:role",Long.class)
            .setParameter("id",actor.userId()).setParameter("active",UserStatus.ACTIVE).setParameter("role",RoleCode.CUSTOMER).getSingleResult()==0)
            throw ApiExceptions.forbidden("Tài khoản không có quyền truy cập phiên chat.");
    }
    @Transactional(readOnly=true)
    public Session read(ActorPrincipal actor) {
        authorize(actor);var row=em.find(CustomerChatSession.class,actor.userId());
        var messages=List.<Message>of();UUID ticketId=null;boolean autoReturn=false;
        if(row!=null&&row.getExpiresAt().isAfter(now())) {
            try {messages=List.of(mapper.readValue(row.getMessagesJson(),Message[].class));}catch(Exception e){throw new IllegalStateException("Cannot read saved chat session");}
            ticketId=row.getTicketId();autoReturn=row.isAutoReturn();
        } else {
            var ids=em.createQuery("select t.id from SupportTicket t join SupportState s on s.id=t.id where t.customer.id=:customer and t.status in :statuses and not exists (select h.ticketId from SupportHiddenHistory h where h.ticketId=t.id and h.customerId=:customer) and s.conversationPurgedAt is null and (s.conversationExpiresAt is null or s.conversationExpiresAt>:at) order by s.changedAt desc,t.id",UUID.class)
                .setParameter("customer",actor.userId()).setParameter("statuses",List.of(SupportTicketStatus.open,SupportTicketStatus.in_progress,SupportTicketStatus.waiting_customer)).setParameter("at",now()).setMaxResults(1).getResultList();
            if(!ids.isEmpty()){ticketId=ids.get(0);autoReturn=true;}
        }
        if(ticketId!=null&&em.find(SupportHiddenHistory.class,ticketId)!=null){ticketId=null;autoReturn=false;}
        if(ticketId!=null)try {
            var ticket=support.detail(actor,SupportService.Audience.CUSTOMER,ticketId);
            if(autoReturn&&Set.of(SupportTicketStatus.resolved,SupportTicketStatus.closed).contains(ticket.status())){autoReturn=false;messages=List.of();}
        }catch(ApiException e){if(e.getStatus().value()!=404)throw e;ticketId=null;autoReturn=false;}
        return new Session(row==null?0:row.getRevision(),new Snapshot(messages,ticketId,autoReturn));
    }
    public Session save(ActorPrincipal actor,Save body) {
        authorize(actor);
        var owner=em.find(User.class,actor.userId(),LockModeType.PESSIMISTIC_WRITE);em.refresh(owner,LockModeType.PESSIMISTIC_WRITE);
        var snapshot=body.snapshot();
        if(snapshot.messages().stream().mapToLong(m->m.body().length()).sum()>200000||snapshot.messages().stream().map(Message::id).distinct().count()!=snapshot.messages().size())
            throw ApiExceptions.validation("Phiên chat vượt giới hạn lưu trữ.",null);
        if(snapshot.ticketId()!=null)support.detail(actor,SupportService.Audience.CUSTOMER,snapshot.ticketId());
        String json,hash;
        try {json=mapper.writeValueAsString(snapshot.messages());hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(snapshot)));}
        catch(Exception e){throw new IllegalStateException("Cannot encode chat session");}
        var row=em.find(CustomerChatSession.class,actor.userId());
        if(row!=null)em.refresh(row);
        if(row!=null&&body.mutationId().equals(row.getMutationId())) {
            if(!hash.equals(row.getMutationHash()))throw ApiExceptions.conflict("Mã lưu phiên đã được dùng cho nội dung khác.");
            return new Session(row.getRevision(),snapshot);
        }
        if(body.expectedVersion()!=(row==null?0:row.getRevision()))throw ApiExceptions.conflict("Phiên chat đã thay đổi ở cửa sổ khác. Hãy tải lại phiên.");
        boolean fresh=row==null;
        if(fresh){row=new CustomerChatSession();row.setCustomerId(actor.userId());row.setRevision(0);}
        if(!json.equals(row.getMessagesJson())||row.getExpiresAt()==null)row.setExpiresAt(now().plus(Duration.ofDays(10)));
        row.setMessagesJson(json);row.setTicketId(snapshot.ticketId());row.setAutoReturn(snapshot.autoReturn());row.setRevision(row.getRevision()+1);
        row.setMutationId(body.mutationId());row.setMutationHash(hash);if(fresh)em.persist(row);em.flush();
        return new Session(row.getRevision(),snapshot);
    }
    @Scheduled(initialDelay=3600000,fixedDelay=3600000)
    public void sweep(){em.createQuery("delete from CustomerChatSession s where s.expiresAt<=:at").setParameter("at",now()).executeUpdate();}
}
