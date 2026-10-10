package com.storagehub.service.renewal.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal primitives only. Caller must authorize and validate authoritative terms before writing. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class RenewalPersistence {
    private final EntityManager em;
    private final ObjectMapper mapper;
    public Rental lockRental(UUID id) {
        Rental rental=em.find(Rental.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(rental==null)throw ApiExceptions.notFound("Rental was not found");return rental;
    }
    public RenewalQuote storeQuote(Rental rental,User actor,Object serverTerms,Instant now,Instant expiresAt) {
        if(rental.getId()==null||actor.getId()==null||!actor.getId().equals(rental.getCustomer().getId()))
            throw ApiExceptions.conflict("Quote customer must match rental customer");
        String json=canonical(serverTerms);
        RenewalQuote quote=new RenewalQuote(rental,actor,json,hash(json),now,expiresAt);em.persist(quote);return quote;
    }
    public RenewalQuote validQuote(UUID id,Rental rental,User actor,Instant now) {
        RenewalQuote quote=em.find(RenewalQuote.class,id);
        if(quote==null||!quote.getRental().getId().equals(rental.getId())||!quote.getCustomer().getId().equals(actor.getId()))
            throw ApiExceptions.notFound("Renewal quote was not found");
        if(!now.isBefore(quote.getExpiresAt()))throw ApiExceptions.conflict("Renewal quote has expired");
        if(em.createQuery("select count(x) from RenewalAcceptedRevision x where x.quote.id=:id",Long.class).setParameter("id",id).getSingleResult()>0)
            throw ApiExceptions.conflict("Renewal quote is already accepted");
        return quote;
    }
    /** Caller holds Rental write lock. Database PK also enforces one slot across processes. */
    public RenewalWorkflow acceptInitial(Rental rental,Renewal renewal,RenewalQuote quote,Instant now,String note) {
        if(note!=null&&note.length()>2000)throw ApiExceptions.validation("Note exceeds 2000 characters",null);
        lockRental(rental.getId());
        if(!rental.getId().equals(renewal.getRental().getId()))throw ApiExceptions.conflict("Open slot rental does not match renewal");
        if(em.find(RenewalOpenSlot.class,rental.getId())!=null)throw ApiExceptions.conflict("Rental already has an open renewal");
        requireBinding(renewal,quote);
        if(!now.isBefore(quote.getExpiresAt()))throw ApiExceptions.conflict("Renewal quote has expired");
        var revision=new RenewalAcceptedRevision(renewal,quote,1,now,note);em.persist(revision);
        var workflow=new RenewalWorkflow(renewal,revision);em.persist(workflow);
        em.persist(new RenewalOpenSlot(rental,renewal));em.flush();return workflow;
    }
    public RenewalWorkflow lockWorkflow(UUID renewalId,long expectedVersion) {
        var workflow=em.find(RenewalWorkflow.class,renewalId,LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null)throw ApiExceptions.conflict("Legacy renewal has no verified workflow state");
        em.refresh(workflow,LockModeType.PESSIMISTIC_WRITE);
        if(workflow.getVersion()!=expectedVersion)throw ApiExceptions.conflict("Renewal version is stale");return workflow;
    }
    public void acceptRevision(RenewalWorkflow workflow,RenewalQuote quote,Instant now,String note) {
        requireBinding(workflow.getRenewal(),quote);
        if(!now.isBefore(quote.getExpiresAt()))throw ApiExceptions.conflict("Renewal quote has expired");
        if(workflow.getAcceptedRevision().getQuote().getId().equals(quote.getId()))throw ApiExceptions.conflict("Quote is already accepted");
        if(note!=null&&note.length()>2000)throw ApiExceptions.validation("Note exceeds 2000 characters",null);
        var revision=new RenewalAcceptedRevision(workflow.getRenewal(),quote,workflow.getAcceptedRevision().getRevisionNumber()+1,now,note);
        em.persist(revision);workflow.accept(revision);em.flush();
    }
    public void releaseSlot(RenewalWorkflow workflow) {
        if(!Set.of(RenewalStatus.rejected,RenewalStatus.cancelled,RenewalStatus.completed).contains(workflow.getRenewal().getStatus()))
            throw ApiExceptions.conflict("Nonterminal renewal cannot release its open slot");
        var slot=em.find(RenewalOpenSlot.class,workflow.getRenewal().getRental().getId(),LockModeType.PESSIMISTIC_WRITE);
        if(slot==null||!slot.getRenewal().getId().equals(workflow.getId()))throw ApiExceptions.conflict("Open slot does not match renewal");
        em.remove(slot);
    }
    /** Recheck current actor/resource permission BEFORE invoking replay. No cached correlation ID. */
    public Optional<Replay> replay(User actor,String operation,String key,UUID resource,Object payload) {
        validateKey(operation,key);String hash=hash(canonical(payload));
        var rows=em.createQuery("select x from RenewalIdempotency x where x.actor.id=:actor and x.operation=:operation and x.requestKeyHash=:key",RenewalIdempotency.class)
            .setParameter("actor",actor.getId()).setParameter("operation",operation).setParameter("key",hash(key)).getResultList();
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        if(!row.getResourceId().equals(resource)||!row.getPayloadHash().equals(hash))throw ApiExceptions.conflict("Idempotency key reused with different request");
        return Optional.of(new Replay(row.getHttpStatus(),row.getResultJson()));
    }
    public void remember(User actor,String operation,String key,UUID resource,Object payload,int status,Object data) {
        validateKey(operation,key);
        if(status<200||status>=300)throw new IllegalArgumentException("Only committed successful data is cached");
        String result;
        try{result=mapper.writeValueAsString(data);}catch(Exception e){throw new IllegalArgumentException("Invalid command result",e);}
        em.persist(new RenewalIdempotency(actor,operation,key,resource,hash(canonical(payload)),status,result));em.flush();
    }
    private static void requireBinding(Renewal renewal,RenewalQuote quote) {
        if(renewal.getId()==null||!renewal.getRental().getId().equals(quote.getRental().getId())||!renewal.getRequestedBy().getId().equals(quote.getCustomer().getId()))
            throw ApiExceptions.conflict("Accepted quote does not match renewal owner/rental");
        if(renewal.getStatus()!=RenewalStatus.pending)throw ApiExceptions.conflict("Only pending renewal accepts terms");
    }
    private static void validateKey(String operation,String key) {
        if(operation==null||operation.isBlank()||operation.length()>32||key==null||key.isBlank()||key.length()>100)
            throw ApiExceptions.validation("Operation and Idempotency-Key (1–100) required",null);
    }
    public String canonical(Object value) {
        if(value==null)throw new IllegalArgumentException("Snapshot/payload must not be null");
        try{return mapper.writeValueAsString(sort(mapper.valueToTree(value)));}catch(Exception e){throw new IllegalArgumentException("Invalid snapshot/payload",e);}
    }
    private JsonNode sort(JsonNode node) {
        if(node.isObject()){ObjectNode result=mapper.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(n->result.set(n,sort(node.get(n))));return result;}
        if(node.isArray()){ArrayNode result=mapper.createArrayNode();node.forEach(n->result.add(sort(n)));return result;}return node;
    }
    private static String hash(String json) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public record Replay(int httpStatus,String dataJson) {}
}
