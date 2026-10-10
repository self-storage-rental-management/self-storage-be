package com.storagehub.service.ledger;

import com.storagehub.common.api.ApiExceptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Operational subledger, not a second Booking payment engine or a general accounting ledger.
 * No Hibernate entities or automatic DDL. Every writer MUST join the caller transaction.
 * Creating an account never certifies coverage of historical/shared obligations.
 */
@Component
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
public class LedgerStore {
    public enum Kind { RENT, SECURITY_DEPOSIT, RENEWAL_DEPOSIT, RENEWAL_REMAINDER }
    public enum Method { CASH, VERIFIED_BANK, SIMULATED }
    public record Obligation(UUID id,UUID renewalId,Kind kind,BigDecimal amount,Instant dueAt,
        String sourceRef,BigDecimal allocated,BigDecimal refunded,BigDecimal outstanding) {}
    public record Receipt(UUID id,Method method,BigDecimal amount,String evidenceRef,UUID evidenceFileId,Instant receivedAt) {}
    public record Refund(UUID id,UUID receiptId,UUID obligationId,BigDecimal amount,String status,String decisionRef,String payoutRef) {}
    public record Snapshot(UUID rentalId,UUID customerId,UUID facilityId,long revision,List<Obligation> obligations,List<Receipt> receipts,List<Refund> refunds) {
        public BigDecimal knownOutstanding(){return obligations.stream().map(Obligation::outstanding).reduce(BigDecimal.ZERO,BigDecimal::add);}
    }
    private final JdbcTemplate jdbc;
    public LedgerStore(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public void open(UUID rental,UUID customer,UUID facility) {
        writeTransaction();required(rental);required(customer);required(facility);
        var rows=jdbc.queryForList("select customer_id,facility_id from duong_ledger_accounts where rental_id=? for update",s(rental));
        if(rows.isEmpty())jdbc.update("insert into duong_ledger_accounts(rental_id,customer_id,facility_id,revision) values(?,?,?,0)",s(rental),s(customer),s(facility));
        else if(!s(customer).equals(value(rows.getFirst(),"customer_id"))||!s(facility).equals(value(rows.getFirst(),"facility_id")))throw ApiExceptions.conflict("Ledger resource binding changed");
    }
    public Optional<Snapshot> read(UUID rental) {
        required(rental);
        var accounts=jdbc.queryForList("select revision,customer_id,facility_id from duong_ledger_accounts where rental_id=?",s(rental));
        if(accounts.isEmpty())return Optional.empty();var account=accounts.getFirst();long revision=Long.parseLong(value(account,"revision"));
        var obligations=jdbc.query("select o.*,coalesce((select sum(a.amount) from duong_ledger_allocations a join duong_ledger_receipts p on p.id=a.receipt_id where a.obligation_id=o.id and p.method<>'SIMULATED'),0) as allocated,coalesce((select sum(f.amount) from duong_ledger_refunds f where f.obligation_id=o.id and f.status='EXECUTED'),0) as refunded from duong_ledger_obligations o where o.rental_id=? order by o.due_at_ms,o.id",(r,i)->obligation(r),s(rental));
        var receipts=jdbc.query("select * from duong_ledger_receipts where rental_id=? order by received_at_ms,id",(r,i)->receipt(r),s(rental));
        var refunds=jdbc.query("select * from duong_ledger_refunds where rental_id=? order by id",(r,i)->refund(r),s(rental));
        long after=jdbc.queryForObject("select revision from duong_ledger_accounts where rental_id=?",Long.class,s(rental));
        if(after!=revision)throw ApiExceptions.conflict("Ledger changed during read, reload the account");
        return Optional.of(new Snapshot(rental,uuid(value(account,"customer_id")),uuid(value(account,"facility_id")),after,List.copyOf(obligations),List.copyOf(receipts),List.copyOf(refunds)));
    }
    public UUID charge(UUID rental,UUID renewal,Kind kind,BigDecimal amount,Instant due,String source,
        UUID actor,String key,long expectedRevision) {
        long revision=lock(rental);money(amount,true);required(kind);required(due);text(source,200);
        String fingerprint=hash(List.of(s(rental),renewal==null?"":s(renewal),kind.name(),amount.toPlainString(),due.toEpochMilli(),source).toString());
        var replay=replay(rental,actor,"CHARGE",key,fingerprint);if(replay.isPresent())return replay.get();version(revision,expectedRevision);
        var existing=jdbc.query("select * from duong_ledger_obligations where rental_id=? and source_ref=? and kind=?",(r,i)->obligationWithoutTotals(r),s(rental),source,kind.name());
        UUID id;
        if(!existing.isEmpty()) {
            var old=existing.getFirst();if(!Objects.equals(old.renewalId(),renewal)||old.amount().compareTo(amount)!=0||old.dueAt().toEpochMilli()!=due.toEpochMilli())throw ApiExceptions.conflict("Source charge was already posted with different terms");id=old.id();
        }else {
            id=UUID.randomUUID();jdbc.update("insert into duong_ledger_obligations(id,rental_id,renewal_id,kind,amount,due_at_ms,source_ref) values(?,?,?,?,?,?,?)",s(id),s(rental),renewal==null?null:s(renewal),kind.name(),amount,due.toEpochMilli(),source);bump(rental);
        }
        remember(rental,actor,"CHARGE",key,fingerprint,id);return id;
    }
    public UUID receive(UUID rental,Method method,BigDecimal amount,Map<UUID,BigDecimal> allocations,
        String evidenceRef,UUID evidenceFile,UUID actor,String key,long expectedRevision,Instant receivedAt) {
        long revision=lock(rental);required(method);money(amount,false);text(evidenceRef,200);required(evidenceFile);required(receivedAt);
        if(allocations==null||allocations.isEmpty()||allocations.size()>100)throw ApiExceptions.validation("Explicit payment allocations required",null);
        var ordered=new TreeMap<UUID,BigDecimal>();allocations.forEach((id,value)->{required(id);money(value,false);ordered.put(id,value);});
        if(ordered.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(amount)!=0)throw ApiExceptions.validation("Receipt amount must equal allocated amount",null);
        String fingerprint=hash(List.of(s(rental),method.name(),amount.toPlainString(),ordered.toString(),evidenceRef,s(evidenceFile)).toString());
        var replay=replay(rental,actor,"RECEIPT",key,fingerprint);if(replay.isPresent())return replay.get();version(revision,expectedRevision);
        if(jdbc.queryForObject("select count(*) from duong_ledger_receipts where evidence_ref=?",Long.class,evidenceRef)!=0)throw ApiExceptions.conflict("Receipt evidence was already used");
        var snapshot=read(rental).orElseThrow();
        for(var entry:ordered.entrySet()) {
            var o=snapshot.obligations().stream().filter(v->v.id().equals(entry.getKey())).findFirst().orElseThrow(()->ApiExceptions.notFound("Obligation not found in this rental"));
            if(method!=Method.SIMULATED&&entry.getValue().compareTo(o.outstanding())>0)throw ApiExceptions.conflict("Receipt exceeds the unpaid obligation");
        }
        UUID id=UUID.randomUUID();jdbc.update("insert into duong_ledger_receipts(id,rental_id,method,amount,evidence_ref,evidence_file_id,actor_id,received_at_ms) values(?,?,?,?,?,?,?,?)",s(id),s(rental),method.name(),amount,evidenceRef,s(evidenceFile),s(actor),receivedAt.toEpochMilli());
        ordered.forEach((o,value)->jdbc.update("insert into duong_ledger_allocations(receipt_id,obligation_id,rental_id,amount) values(?,?,?,?)",s(id),s(o),s(rental),value));
        bump(rental);remember(rental,actor,"RECEIPT",key,fingerprint,id);return id;
    }
    public UUID reserveRefund(UUID rental,UUID receipt,UUID obligation,BigDecimal amount,String decisionRef,
        UUID actor,String key,long expectedRevision) {
        long revision=lock(rental);required(receipt);required(obligation);money(amount,false);text(decisionRef,200);
        String fingerprint=hash(List.of(s(rental),s(receipt),s(obligation),amount.toPlainString(),decisionRef).toString());
        var replay=replay(rental,actor,"RESERVE_REFUND",key,fingerprint);if(replay.isPresent())return replay.get();version(revision,expectedRevision);
        var paid=jdbc.query("select a.amount from duong_ledger_allocations a join duong_ledger_receipts p on p.id=a.receipt_id where p.rental_id=? and p.id=? and a.obligation_id=? and p.method<>'SIMULATED'",(r,i)->r.getBigDecimal(1),s(rental),s(receipt),s(obligation));
        if(paid.isEmpty())throw ApiExceptions.notFound("Actual allocated receipt not found");
        BigDecimal reserved=jdbc.queryForObject("select coalesce(sum(amount),0) from duong_ledger_refunds where receipt_id=? and obligation_id=? and status in ('RESERVED','EXECUTED')",BigDecimal.class,s(receipt),s(obligation));
        if(amount.add(reserved).compareTo(paid.getFirst())>0)throw ApiExceptions.conflict("Refund exceeds available paid funds");
        UUID id=UUID.randomUUID();jdbc.update("insert into duong_ledger_refunds(id,rental_id,receipt_id,obligation_id,amount,status,decision_ref) values(?,?,?,?,?,'RESERVED',?)",s(id),s(rental),s(receipt),s(obligation),amount,decisionRef);
        bump(rental);remember(rental,actor,"RESERVE_REFUND",key,fingerprint,id);return id;
    }
    /** Only an executor with actual payout evidence may call this; approval is never a transfer. */
    public UUID executeRefund(UUID rental,UUID refund,String payoutRef,UUID actor,String key,long expectedRevision) {
        long revision=lock(rental);required(refund);text(payoutRef,200);String fingerprint=hash(List.of(s(rental),s(refund),payoutRef).toString());
        var replay=replay(rental,actor,"EXECUTE_REFUND",key,fingerprint);if(replay.isPresent())return replay.get();version(revision,expectedRevision);
        var rows=jdbc.query("select * from duong_ledger_refunds where rental_id=? and id=?",(r,i)->refund(r),s(rental),s(refund));
        if(rows.isEmpty())throw ApiExceptions.notFound("Refund reservation not found");if(!rows.getFirst().status().equals("RESERVED"))throw ApiExceptions.conflict("Refund is not awaiting execution");
        if(jdbc.queryForObject("select count(*) from duong_ledger_refunds where payout_ref=?",Long.class,payoutRef)!=0)throw ApiExceptions.conflict("Payout evidence was already used");
        jdbc.update("update duong_ledger_refunds set status='EXECUTED',payout_ref=? where id=?",payoutRef,s(refund));bump(rental);remember(rental,actor,"EXECUTE_REFUND",key,fingerprint,refund);return refund;
    }
    private long lock(UUID rental){writeTransaction();required(rental);var rows=jdbc.query("select revision from duong_ledger_accounts where rental_id=? for update",(r,i)->r.getLong(1),s(rental));if(rows.isEmpty())throw ApiExceptions.conflict("Ledger account has not been opened");return rows.getFirst();}
    private Optional<UUID> replay(UUID rental,UUID actor,String operation,String key,String fingerprint) {
        required(actor);text(key,128);
        var rows=jdbc.queryForList("select rental_id,payload_hash,result_id from duong_ledger_commands where actor_id=? and operation=? and key_hash=?",s(actor),operation,hash(key));
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        if(!s(rental).equals(value(row,"rental_id"))||!fingerprint.equals(value(row,"payload_hash")))throw ApiExceptions.conflict("Idempotency key belongs to a different request");return Optional.of(UUID.fromString(value(row,"result_id")));
    }
    private void remember(UUID rental,UUID actor,String op,String key,String fingerprint,UUID result){jdbc.update("insert into duong_ledger_commands(actor_id,operation,key_hash,rental_id,payload_hash,result_id) values(?,?,?,?,?,?)",s(actor),op,hash(key),s(rental),fingerprint,s(result));}
    private void bump(UUID rental){jdbc.update("update duong_ledger_accounts set revision=revision+1 where rental_id=?",s(rental));}
    private static String value(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)v=row.get(key.toUpperCase(Locale.ROOT));return v==null?null:v.toString();}
    private static void writeTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Ledger writes require a caller write transaction");}
    private static void version(long actual,long expected){if(expected<0||actual!=expected)throw ApiExceptions.conflict("Ledger revision changed, reload before submitting");}
    private static void money(BigDecimal value,boolean zero){if(value==null||value.signum()<0||!zero&&value.signum()==0||value.stripTrailingZeros().scale()>0||value.precision()-value.scale()>18)throw ApiExceptions.validation("VND amount must be a nonnegative integer within supported range",null);}
    private static void required(Object value){if(value==null)throw ApiExceptions.validation("Required ledger field missing",null);}
    private static void text(String value,int limit){if(value==null||value.isBlank()||value.length()>limit||!value.equals(value.trim()))throw ApiExceptions.validation("Invalid ledger reference",null);}
    private static String s(UUID id){return id.toString();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static UUID uuid(String v){return v==null?null:UUID.fromString(v);}
    private static Obligation obligationWithoutTotals(ResultSet r)throws SQLException{return new Obligation(uuid(r.getString("id")),uuid(r.getString("renewal_id")),Kind.valueOf(r.getString("kind")),r.getBigDecimal("amount"),Instant.ofEpochMilli(r.getLong("due_at_ms")),r.getString("source_ref"),BigDecimal.ZERO,BigDecimal.ZERO,r.getBigDecimal("amount"));}
    private static Obligation obligation(ResultSet r)throws SQLException {var o=obligationWithoutTotals(r);var allocated=r.getBigDecimal("allocated");var refunded=r.getBigDecimal("refunded");var outstanding=o.amount().subtract(allocated).add(refunded);if(outstanding.signum()<0)throw ApiExceptions.conflict("Ledger allocations are inconsistent");return new Obligation(o.id(),o.renewalId(),o.kind(),o.amount(),o.dueAt(),o.sourceRef(),allocated,refunded,outstanding);}
    private static Receipt receipt(ResultSet r)throws SQLException{return new Receipt(uuid(r.getString("id")),Method.valueOf(r.getString("method")),r.getBigDecimal("amount"),r.getString("evidence_ref"),uuid(r.getString("evidence_file_id")),Instant.ofEpochMilli(r.getLong("received_at_ms")));}
    private static Refund refund(ResultSet r)throws SQLException{return new Refund(uuid(r.getString("id")),uuid(r.getString("receipt_id")),uuid(r.getString("obligation_id")),r.getBigDecimal("amount"),r.getString("status"),r.getString("decision_ref"),r.getString("payout_ref"));}
}
