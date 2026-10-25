package com.storagehub.service.support;

import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.*;
import com.storagehub.service.communication.NotificationOutbox;
import jakarta.persistence.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Erases handed-over conversation content; keeps only technical workflow/receipt tombstones. */
@Service @RequiredArgsConstructor @Slf4j
public class SupportConversationRetention {
    private final EntityManager em;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final ObjectProvider<Clock> clocks;
    private final ObjectProvider<NotificationOutbox> outboxes;
    private final ObjectProvider<FileProperties> fileProperties;
    private UUID fileCursor;

    @Scheduled(initialDelayString="${app.support.retention.initial-delay-ms:60000}",fixedDelayString="${app.support.retention.delay-ms:60000}")
    @Transactional
    public void sweep() {
        var at=now();
        var ids=em.createQuery("select s.id from SupportState s where s.conversationExpiresAt<=:now and s.conversationPurgedAt is null order by s.conversationExpiresAt,s.id",UUID.class)
            .setParameter("now",at).setMaxResults(50).getResultList();
        for(var id:ids)purge(id,at);
        // Retry disk cleanup after transient failures, including across restarts.
        var fileQuery=em.createQuery("select f from FileAsset f where f.status=:status and f.entityType in :types"+(fileCursor==null?"":" and f.id>:cursor")+" order by f.id",FileAsset.class)
            .setParameter("status",FileAssetStatus.DELETED).setParameter("types",List.of("DUONG_SUPPORT_PUBLIC","DUONG_SUPPORT_INTERNAL")).setMaxResults(100);
        if(fileCursor!=null)fileQuery.setParameter("cursor",fileCursor);
        var deleted=fileQuery.getResultList();
        fileCursor=deleted.isEmpty()?null:deleted.getLast().getId();
        for(var file:deleted) {
            var state=file.getEntityId()==null?null:em.find(SupportState.class,file.getEntityId());
            if(state!=null&&state.getConversationPurgedAt()!=null)scheduleDiskRemoval(file.getId(),file.getStorageKey());
        }
    }

    private void purge(UUID id,Instant at) {
        // Same ticket -> state locking order as message commands. Recheck after acquiring locks.
        var ticket=em.find(SupportTicket.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(ticket==null)return;
        em.refresh(ticket,LockModeType.PESSIMISTIC_WRITE);
        var state=em.find(SupportState.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(state==null)return;
        em.refresh(state,LockModeType.PESSIMISTIC_WRITE);
        if(state.getConversationPurgedAt()!=null||!state.conversationExpired(at))return;

        em.createQuery("delete from SupportHiddenHistory h where h.ticketId=:id").setParameter("id",id).executeUpdate();
        em.createQuery("delete from SupportMessageRead r where r.ticketId=:id").setParameter("id",id).executeUpdate();
        em.createQuery("delete from SupportMessage m where m.ticket.id=:id").setParameter("id",id).executeUpdate();
        em.createQuery("delete from SupportTicketMessage m where m.ticket.id=:id").setParameter("id",id).executeUpdate();
        em.createQuery("update SupportEvent e set e.reason=:text where e.ticket.id=:id").setParameter("id",id).setParameter("text","Conversation expired").executeUpdate();
        em.createQuery("update SupportEscalation e set e.reason=:text,e.decisionReason=null,e.evidenceJson='[]' where e.ticket.id=:id")
            .setParameter("id",id).setParameter("text","Conversation expired").executeUpdate();
        // Receipt payloads are hashes. Erase cached response bodies but retain keys to prevent replay resurrection.
        var receipts=em.createQuery("select r from SupportCommandReceipt r where r.resultJson like :id",SupportCommandReceipt.class)
            .setParameter("id","%"+id+"%").getResultList();
        for(var receipt:receipts) {
            try {
                var result=mapper.readTree(receipt.getResultJson());
                // A live follow-up may reference this ticket as its parent: never erase its receipt.
                if(id.toString().equals(result.path("ticketId").asText())||id.toString().equals(result.path("id").asText()))receipt.eraseExpiredResponse();
            } catch(java.io.IOException failure) { throw new IllegalStateException("Invalid Support receipt",failure); }
        }
        var outbox=outboxes.getIfAvailable();if(outbox!=null)outbox.purgeSupportConversation(id);
        em.createQuery("delete from Notification n where n.type=:type and n.relatedEntityId=:id")
            .setParameter("id",id).setParameter("type",NotificationType.SUPPORT).executeUpdate();
        var files=em.createQuery("select f from FileAsset f where f.entityId=:id and f.entityType in :types",FileAsset.class)
            .setParameter("id",id).setParameter("types",List.of("DUONG_SUPPORT_PUBLIC","DUONG_SUPPORT_INTERNAL")).getResultList();
        for(var file:files) {
            file.setStatus(FileAssetStatus.DELETED);file.setOriginalName("expired");
        }
        ticket.setSubject("Cuộc trò chuyện đã hết hạn");ticket.setDescription("Nội dung đã được xóa sau 10 ngày không có tin nhắn mới.");
        ticket.setStatus(SupportTicketStatus.closed);state.purgeConversation(at);
    }

    private void scheduleDiskRemoval(UUID id,String key) {
        if(key==null||key.isBlank())return;
        var properties=fileProperties.getIfAvailable();if(properties==null)return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    Path root=Path.of(properties.getStoragePath()).toRealPath();
                    Path target=Path.of(key).toAbsolutePath().normalize();
                    if(!target.startsWith(root)||target.equals(root))throw new IllegalStateException("Outside storage root");
                    if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
                    if(Files.isSymbolicLink(target)||!target.toRealPath().startsWith(root)||!Files.isRegularFile(target))throw new IllegalStateException("Invalid stored file");
                    Files.deleteIfExists(target);
                } catch(Exception failure) { log.warn("Support attachment cleanup deferred for {} ({})",id,failure.getClass().getSimpleName()); }
            }
        });
    }
    private Instant now(){var clock=clocks.getIfAvailable();return(clock==null?Clock.systemUTC():clock).instant();}
}
