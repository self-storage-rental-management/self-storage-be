package com.storagehub.service.ledger;

import com.storagehub.common.api.ApiExceptions;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable cancellation credits. Original accepted terms, receipts and allocations remain intact. */
@Component @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-simple-payment.enabled"},havingValue="true")
public class RenewalLedgerAdjustments {
    private final JdbcTemplate jdbc;
    public Map<UUID,BigDecimal> credits(UUID rental) {
        var result=new HashMap<UUID,BigDecimal>();
        jdbc.query("select obligation_id,sum(amount) as amount from renewal_ledger_cancellation_credits where rental_id=? group by obligation_id",
            rs->{result.put(UUID.fromString(rs.getString("obligation_id")),rs.getBigDecimal("amount"));},rental.toString());
        return result;
    }
    public void cancelOutstanding(LedgerStore.Snapshot snapshot,LedgerStore.Obligation obligation,BigDecimal amount,String source,String reason) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw ApiExceptions.conflict("Cancellation accounting requires a writable transaction");
        if(amount==null||amount.signum()<0||amount.compareTo(obligation.outstanding())>0||!snapshot.obligations().contains(obligation))
            throw ApiExceptions.conflict("Cancellation credit exceeds the verified obligation");
        if(amount.signum()==0)return;
        Long revision=jdbc.queryForObject("select revision from rental_ledger_accounts where rental_id=? for update",Long.class,snapshot.rentalId().toString());
        if(revision==null||revision!=snapshot.revision())throw ApiExceptions.conflict("Ledger changed during cancellation");
        var old=jdbc.queryForList("select obligation_id,amount from renewal_ledger_cancellation_credits where rental_id=? and source_ref=?",snapshot.rentalId().toString(),source);
        if(!old.isEmpty())throw ApiExceptions.conflict("Cancellation credit was already recorded");
        jdbc.update("insert into renewal_ledger_cancellation_credits(id,rental_id,obligation_id,amount,source_ref,reason) values(?,?,?,?,?,?)",
            UUID.randomUUID().toString(),snapshot.rentalId().toString(),obligation.id().toString(),amount,source,reason);
        jdbc.update("update rental_ledger_accounts set revision=revision+1 where rental_id=?",snapshot.rentalId().toString());
    }
}
