package com.storagehub.service.ledger;

import com.storagehub.common.api.ApiExceptions;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Disposition only: the original deposit receipt/allocation remains intact. */
@Component @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-simple-payment.enabled"},havingValue="true")
public class RenewalDepositForfeitures {
    private final JdbcTemplate jdbc;

    public boolean containsReceipt(UUID receipt) {
        return jdbc.queryForObject("select count(*) from renewal_deposit_forfeitures where receipt_id=?",Long.class,receipt.toString())>0;
    }

    public void record(UUID renewal,UUID payment,LedgerStore.Snapshot account,LedgerStore.Obligation obligation,
        LedgerStore.Receipt receipt,String environment,Instant at) {
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
            renewal.toString(),account.rentalId().toString(),payment.toString(),receipt.id().toString(),obligation.id().toString(),receipt.amount(),environment,Timestamp.from(at),"OVERDUE_DAY_8");
        jdbc.update("update rental_ledger_accounts set revision=revision+1 where rental_id=?",account.rentalId().toString());
    }
}
