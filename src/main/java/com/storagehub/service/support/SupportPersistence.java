package com.storagehub.service.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.storagehub.common.api.ApiExceptions;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Standalone Support receipts. No dependency on Renewal tables or business logic. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class SupportPersistence {
    private final EntityManager em;
    private final ObjectMapper mapper;
    public <T> Optional<T> replay(UUID actor,String operation,String key,Object payload,Class<T> type) {
        validate(key);
        var rows=em.createQuery("select r from SupportCommandReceipt r where r.actorId=:actor and r.operation=:op and r.keyHash=:key",SupportCommandReceipt.class)
            .setParameter("actor",actor).setParameter("op",operation).setParameter("key",hash(key)).getResultList();
        if(rows.isEmpty())return Optional.empty();
        var row=rows.getFirst();if(!row.getPayloadHash().equals(hash(canonical(payload))))throw ApiExceptions.conflict("Idempotency key reused with a different Support request");
        if("{\"expired\":true}".equals(row.getResultJson()))throw ApiExceptions.notFound("Cuộc trò chuyện đã hết thời hạn lưu trữ.");
        // Do not replay a response containing expired conversation text before the sweep runs.
        try {
            var tree=mapper.readTree(row.getResultJson());
            String candidate=tree.hasNonNull("ticketId")?tree.get("ticketId").asText():tree.hasNonNull("id")?tree.get("id").asText():null;
            if(candidate!=null) {
                var retention=em.find(SupportState.class,UUID.fromString(candidate));
                if(retention!=null&&retention.conversationExpired(now()))throw ApiExceptions.notFound("Cuộc trò chuyện đã hết thời hạn lưu trữ.");
            }
        } catch(com.storagehub.common.api.ApiException e){throw e;} catch(Exception ignored) { /* Existing decoding below remains authoritative. */ }
        try{return Optional.of(mapper.readValue(row.getResultJson(),type));}catch(Exception e){throw ApiExceptions.conflict("Stored Support command result is invalid");}
    }
    public void remember(UUID actor,String operation,String key,Object payload,Object result) {
        validate(key);em.persist(new SupportCommandReceipt(actor,operation,hash(key),hash(canonical(payload)),json(result)));em.flush();
    }
    public String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid Support JSON",e);}}
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<java.time.Clock> clocks;
    private java.time.Instant now(){var clock=clocks==null?null:clocks.getIfAvailable();return(clock==null?java.time.Clock.systemUTC():clock).instant();}
    public List<UUID> files(String value) {try{return Arrays.asList(mapper.readValue(value,UUID[].class));}catch(Exception e){throw ApiExceptions.conflict("Support evidence references inconsistent");}}
    private String canonical(Object value) {return json(sort(mapper.valueToTree(value)));}
    private JsonNode sort(JsonNode node) {
        if(node.isObject()){ObjectNode result=mapper.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(n->result.set(n,sort(node.get(n))));return result;}
        if(node.isArray()){ArrayNode result=mapper.createArrayNode();node.forEach(n->result.add(sort(n)));return result;}return node;
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void validate(String key){if(key==null||key.isBlank()||key.length()>100)throw ApiExceptions.validation("Idempotency-Key of 1-100 characters required",null);}
}
