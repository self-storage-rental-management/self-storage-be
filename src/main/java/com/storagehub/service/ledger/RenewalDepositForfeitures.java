package com.storagehub.service.ledger;

import com.storagehub.common.api.ApiExceptions;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Disposition only: the original deposit receipt/allocation remains intact. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-simple-payment.enabled"},havingValue="true")
public class RenewalDepositForfeitures {
    private final JdbcTemplate jdbc;

    public boolean containsReceipt(UUID receipt) {
        return jdbc.queryForObject("select count(*) from renewal_deposit_forfeitures where receipt_id=?",Long.class,receipt.toString())>0
            || jdbc.queryForObject("select count(*) from renewal_payment_forfeitures where receipt_id=?",Long.class,receipt.toString())>0;
    }

    public record PaidReceipt(UUID paymentId,LedgerStore.Receipt receipt,String environment) {}
    public record FullDisposition(BigDecimal amount,String environment) {}
    public java.util.Optional<FullDisposition> fullDisposition(UUID renewal) {
        var rows=jdbc.queryForList("select amount,environment from renewal_payment_forfeitures where renewal_id=?",renewal.toString());
        if(rows.isEmpty())return java.util.Optional.empty();
        var amount=rows.stream().map(r->new BigDecimal(r.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
        var environments=rows.stream().map(r->r.get("environment").toString()).distinct().toList();
        return java.util.Optional.of(new FullDisposition(amount,environments.size()==1?environments.getFirst():"MIXED"));
    }

    /** One atomic disposition across the full payment, including a deposit paid separately. */
    public void recordFullyPaid(UUID renewal,LedgerStore.Snapshot account,java.util.List<PaidReceipt> paid,Instant at) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw ApiExceptions.conflict("Payment forfeiture requires a writable transaction");
        if(paid.isEmpty()||paid.stream().map(p->p.receipt().id()).distinct().count()!=paid.size())
            throw ApiExceptions.conflict("Unique paid renewal receipts required");
        Long revision=jdbc.queryForObject("select revision from rental_ledger_accounts where rental_id=? for update",Long.class,account.rentalId().toString());
        if(revision==null||revision!=account.revision())throw ApiExceptions.conflict("Ledger changed during payment forfeiture");
        boolean changed=false;
        for(var p:paid) {
            var receipt=p.receipt();
            if(!account.receipts().contains(receipt)||receipt.amount().signum()<=0
                ||!("SANDBOX".equals(p.environment())&&receipt.method()==LedgerStore.Method.VNPAY_SANDBOX
                    ||"PRODUCTION".equals(p.environment())&&java.util.Set.of(LedgerStore.Method.CASH,LedgerStore.Method.VERIFIED_BANK).contains(receipt.method())))
                throw ApiExceptions.conflict("Verified allocated renewal payment required");
            var allocations=jdbc.queryForList("select a.obligation_id,a.amount from rental_ledger_allocations a where a.rental_id=? and a.receipt_id=?",account.rentalId().toString(),receipt.id().toString());
            BigDecimal allocated=BigDecimal.ZERO;
            for(var row:allocations) {
                var obligation=account.obligations().stream().filter(o->o.id().toString().equals(row.get("obligation_id").toString())).findFirst().orElseThrow();
                boolean accepted=renewal.equals(obligation.renewalId())&&java.util.Set.of(LedgerStore.Kind.RENEWAL_DEPOSIT,LedgerStore.Kind.RENEWAL_REMAINDER).contains(obligation.kind());
                boolean overdue=obligation.kind()==LedgerStore.Kind.RENEWAL_REMAINDER&&obligation.sourceRef().startsWith("rental-overdue:"+account.rentalId()+":");
                if((!accepted&&!overdue)||obligation.refunded().signum()!=0||obligation.outstanding().signum()!=0)
                    throw ApiExceptions.conflict("Only fully allocated renewal payments can be forfeited");
                allocated=allocated.add(new BigDecimal(row.get("amount").toString()));
            }
            if(allocated.compareTo(receipt.amount())!=0)throw ApiExceptions.conflict("Renewal receipt allocation mismatch");
            Long refunds=jdbc.queryForObject("select count(*) from rental_ledger_refunds where receipt_id=? and status in ('RESERVED','EXECUTED')",Long.class,receipt.id().toString());
            if(refunds==null||refunds>0)throw ApiExceptions.conflict("Payment has a reserved or executed refund");
            if(jdbc.queryForObject("select count(*) from renewal_deposit_forfeitures where receipt_id=?",Long.class,receipt.id().toString())>0)
                throw ApiExceptions.conflict("Deposit already forfeited separately");
            var existing=jdbc.queryForList("select renewal_id,payment_id,amount from renewal_payment_forfeitures where receipt_id=?",receipt.id().toString());
            if(!existing.isEmpty()) {
                var row=existing.getFirst();
                if(!renewal.toString().equals(row.get("renewal_id"))||!p.paymentId().toString().equals(row.get("payment_id"))
                    ||new BigDecimal(row.get("amount").toString()).compareTo(receipt.amount())!=0)
                    throw ApiExceptions.conflict("Payment forfeiture binding changed");
                continue;
            }
            jdbc.update("insert into renewal_payment_forfeitures(renewal_id,rental_id,payment_id,receipt_id,amount,environment,forfeited_at,reason) values(?,?,?,?,?,?,?,?)",
                renewal.toString(),account.rentalId().toString(),p.paymentId().toString(),receipt.id().toString(),receipt.amount(),p.environment(),Timestamp.from(at),"CUSTOMER_CANCELLED");
            changed=true;
        }
        if(changed)jdbc.update("update rental_ledger_accounts set revision=revision+1 where rental_id=?",account.rentalId().toString());
    }

    public void record(UUID renewal,UUID payment,LedgerStore.Snapshot account,LedgerStore.Obligation obligation,
        LedgerStore.Receipt receipt,String environment,Instant at) {
        record(renewal,payment,account,obligation,receipt,environment,at,"OVERDUE_DAY_8");
    }

    public void record(UUID renewal,UUID payment,LedgerStore.Snapshot account,LedgerStore.Obligation obligation,
        LedgerStore.Receipt receipt,String environment,Instant at,String reason) {
        if (!java.util.Set.of("OVERDUE_DAY_8","CUSTOMER_CANCELLED").contains(reason))
            throw ApiExceptions.conflict("Unsupported deposit forfeiture reason");
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw ApiExceptions.conflict("Deposit forfeiture requires a writable transaction");
        if(obligation.kind()!=LedgerStore.Kind.RENEWAL_DEPOSIT||!renewal.equals(obligation.renewalId())
            ||!account.obligations().contains(obligation)||!account.receipts().contains(receipt)
            ||receipt.amount().signum()<=0||receipt.amount().compareTo(obligation.amount())!=0||obligation.refunded().signum()!=0
            ||obligation.allocated().compareTo(receipt.amount())!=0||obligation.outstanding().signum()!=0
            ||!("SANDBOX".equals(environment)&&receipt.method()==LedgerStore.Method.VNPAY_SANDBOX
                ||"PRODUCTION".equals(environment)&&java.util.Set.of(LedgerStore.Method.CASH,LedgerStore.Method.VERIFIED_BANK).contains(receipt.method())))
            throw ApiExceptions.conflict("Forfeiture requires the original allocated Renewal deposit");
        Long revision=jdbc.queryForObject("select revision from rental_ledger_accounts where rental_id=? for update",Long.class,account.rentalId().toString());
        if(revision==null||revision!=account.revision())throw ApiExceptions.conflict("Ledger changed during deposit forfeiture");
        var existing=jdbc.queryForList("select payment_id,receipt_id,amount from renewal_deposit_forfeitures where renewal_id=?",renewal.toString());
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(!payment.toString().equals(row.get("payment_id"))||!receipt.id().toString().equals(row.get("receipt_id"))
                ||new BigDecimal(row.get("amount").toString()).compareTo(receipt.amount())!=0)
                throw ApiExceptions.conflict("Deposit forfeiture binding changed");
            return;
        }
        Long refunds=jdbc.queryForObject("select count(*) from rental_ledger_refunds where receipt_id=? and status in ('RESERVED','EXECUTED')",Long.class,receipt.id().toString());
        if(refunds==null||refunds>0)throw ApiExceptions.conflict("Deposit has a reserved or executed refund");
        jdbc.update("insert into renewal_deposit_forfeitures(renewal_id,rental_id,payment_id,receipt_id,obligation_id,amount,environment,forfeited_at,reason) values(?,?,?,?,?,?,?,?,?)",
            renewal.toString(),account.rentalId().toString(),payment.toString(),receipt.id().toString(),obligation.id().toString(),receipt.amount(),environment,Timestamp.from(at),reason);
        jdbc.update("update rental_ledger_accounts set revision=revision+1 where rental_id=?",account.rentalId().toString());
    }
}
