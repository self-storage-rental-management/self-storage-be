package com.storagehub.service.communication;

import com.storagehub.common.api.ApiExceptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable IN_APP queue. INBOX means persisted, ACKNOWLEDGED requires an authenticated recipient receipt. */
@Component @ConditionalOnProperty(name="storagehub.integration.communication.enabled",havingValue="true")
public class NotificationOutbox {
    public record Notice(UUID id,UUID recipient,UUID resource,UUID resolutionEvent,String policyRef,String policyVersion,
        String kind,String content,String status,UUID notificationId,Instant acknowledgedAt,long attempts,Instant retryAt) {}
    private final JdbcTemplate jdbc;
    public NotificationOutbox(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public UUID enqueue(UUID recipient,UUID resource,String kind,UUID resolutionEvent,String policyRef,String policyVersion,String content,String key,Instant now){
        transaction();if(recipient==null||resource==null||now==null||kind==null||!Set.of("OVERDUE","SUPPORT_REVIEW").contains(kind)||content==null||content.isBlank()||content.length()>2000||key==null||key.isBlank()||key.length()>128||policyRef==null||policyRef.isBlank()||policyRef.length()>100||policyVersion==null||policyVersion.isBlank()||policyVersion.length()>100||kind.equals("SUPPORT_REVIEW")&&resolutionEvent==null)throw ApiExceptions.validation("Incomplete notification request",null);
        var fingerprint=hash(canonical(recipient.toString(),resource.toString(),kind,Objects.toString(resolutionEvent,""),policyRef,policyVersion,content));
        var rows=jdbc.queryForList("select id,payload_hash from notification_outbox where resource_id=? and kind=? and key_hash=?",resource.toString(),kind,hash(key));
        if(!rows.isEmpty()){var r=rows.getFirst();if(!fingerprint.equals(value(r,"payload_hash")))throw ApiExceptions.conflict("Notification key reused for a different request");return UUID.fromString(value(r,"id"));}
        UUID id=UUID.randomUUID();jdbc.update("insert into notification_outbox(id,recipient_id,resource_id,kind,resolution_event_id,policy_ref,policy_version,content,key_hash,payload_hash,status,attempts,retry_at_ms) values(?,?,?,?,?,?,?,?,?,?,'PENDING',0,?)",id.toString(),recipient.toString(),resource.toString(),kind,resolutionEvent==null?null:resolutionEvent.toString(),policyRef,policyVersion,content,hash(key),fingerprint,now.toEpochMilli());return id;
    }
    public Optional<Notice> read(UUID id,boolean lock){if(lock)transaction();return jdbc.query("select * from notification_outbox where id=?"+(lock?" for update":""),(r,i)->new Notice(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("recipient_id")),UUID.fromString(r.getString("resource_id")),uuid(r.getString("resolution_event_id")),r.getString("policy_ref"),r.getString("policy_version"),r.getString("kind"),r.getString("content"),r.getString("status"),uuid(r.getString("notification_id")),r.getString("acknowledged_at")==null?null:Instant.parse(r.getString("acknowledged_at")),r.getLong("attempts"),Instant.ofEpochMilli(r.getLong("retry_at_ms"))),id.toString()).stream().findFirst();}
    public List<UUID> pending(Instant now){return jdbc.query("select id from notification_outbox where status='PENDING' and retry_at_ms<=? order by retry_at_ms,id limit 50",(r,i)->UUID.fromString(r.getString(1)),now.toEpochMilli());}
    public void inbox(UUID id,UUID notification){transaction();if(notification==null)throw ApiExceptions.validation("Actual notification reference required",null);if(jdbc.update("update notification_outbox set status='INBOX',notification_id=?,attempts=attempts+1 where id=? and status='PENDING'",notification.toString(),id.toString())!=1)throw ApiExceptions.conflict("Notification delivery state changed");}
    public void retry(UUID id,Instant now){transaction();var notice=read(id,true).orElseThrow();if(!notice.status().equals("PENDING"))return;long delay=Math.min(3600,30L*(1L<<Math.min(notice.attempts(),7)));jdbc.update("update notification_outbox set attempts=attempts+1,retry_at_ms=? where id=?",now.plusSeconds(delay).toEpochMilli(),id.toString());}
    public Notice acknowledge(UUID id,UUID recipient,UUID notification,Instant now){transaction();if(notification==null||now==null)throw ApiExceptions.validation("Actual notification receipt required",null);var n=read(id,true).orElseThrow(()->ApiExceptions.notFound("Notice not found"));if(!n.recipient().equals(recipient)||!Objects.equals(n.notificationId(),notification))throw ApiExceptions.notFound("Notice not found");if(n.status().equals("ACKNOWLEDGED"))return n;if(!n.status().equals("INBOX"))throw ApiExceptions.conflict("Notice is not available in the recipient inbox");jdbc.update("update notification_outbox set status='ACKNOWLEDGED',acknowledged_at=? where id=?",now.toString(),id.toString());return read(id,false).orElseThrow();}
    public Optional<Notice> review(UUID ticket,UUID event,String ref,String version,boolean lock){var ids=jdbc.query("select id from notification_outbox where resource_id=? and kind='SUPPORT_REVIEW' and resolution_event_id=? and policy_ref=? and policy_version=? and status='ACKNOWLEDGED'",(r,i)->UUID.fromString(r.getString(1)),ticket.toString(),event.toString(),ref,version);if(ids.size()!=1)return Optional.empty();return read(ids.getFirst(),lock);}
    public Optional<UUID> forNotification(UUID notification,UUID recipient){return jdbc.query("select id from notification_outbox where notification_id=? and recipient_id=?",(r,i)->UUID.fromString(r.getString(1)),notification.toString(),recipient.toString()).stream().findFirst();}
    private static UUID uuid(String v){return v==null?null:UUID.fromString(v);}
    private static String value(Map<String,Object> row,String name){var v=row.get(name);return Objects.toString(v==null?row.get(name.toUpperCase(Locale.ROOT)):v,null);}
    private static String hash(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String canonical(String... values){var b=new StringBuilder();for(var v:values)b.append(v.length()).append(':').append(v);return b.toString();}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Notification writes require caller write transaction");}
}
