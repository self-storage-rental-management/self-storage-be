package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import com.storagehub.service.overdue.OverdueSources;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Persist overdue identity on first observation, independently of list pagination or a viewer. */
@Component @RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="storagehub.record-codes.enabled",havingValue="true",matchIfMissing=true)
public class BusinessRecordCodeCoordinator {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final BusinessRecordCodes codes;
    private final RentalPeriodResolver periods;
    private final ObjectProvider<OverdueSources.TermSource> terms;
    private final ObjectProvider<OverdueSources.FinancialSource> finances;
    private UUID cursor;

    @org.springframework.beans.factory.annotation.Autowired
    private MoneyReceiptCodes receiptCodes;

    @Scheduled(initialDelayString="${storagehub.record-codes.initial-delay-ms:1000}", fixedDelayString="${storagehub.record-codes.delay-ms:30000}")
    @Transactional
    public void registerHistoricalReceipts() {
        var payments = jdbc.queryForList("""
            select bin_to_uuid(id) from payments where status = 'PAID' and receipt_code is null
            order by created_at, id limit 100 for update skip locked
            """, String.class);
        for (String id : payments) {
            String code = receiptCodes.issue("PAYMENT", UUID.fromString(id), false);
            jdbc.update("update payments set receipt_code = ? where id = uuid_to_bin(?) and receipt_code is null", code, id);
        }
        // Offsets are not cash receipts. Historical transfers require an existing matching receipt.
        // Recovery receipts live in an optional module, so only query that table when it was installed.
        String recoveryReceiptEvidence = recoveryReceiptsInstalled()
            ? """
                or exists (select 1 from recovery_settlement_receipts s where s.return_case_id = r.id
                           and s.transaction_reference = r.settlement_payment_id and s.receipt_file_id is not null)"""
            : "";
        var returns = jdbc.query("""
            select bin_to_uuid(r.id), r.net_refund_amount > 0 from return_cases r
            where r.status = 'completed' and r.settlement_paid_at is not null
              and (r.net_refund_amount > 0 or r.amount_due_from_customer > 0)
              and not exists (select 1 from money_receipt_codes m where m.source_type = 'RETURN' and m.source_id = r.id)
              and (exists (select 1 from return_settlement_receipts s where s.return_id = bin_to_uuid(r.id)
                           and s.transaction_reference = r.settlement_payment_id and s.receipt_file_id is not null)
                %s)
            order by r.created_at, r.id limit 100
            """.formatted(recoveryReceiptEvidence),
            (rs, i) -> Map.entry(UUID.fromString(rs.getString(1)), rs.getBoolean(2)));
        returns.forEach(row -> receiptCodes.issue("RETURN", row.getKey(), row.getValue()));
    }

    /** Recovery settlement receipts are created only by the optional recovery module migrations. */
    private boolean recoveryReceiptsInstalled() {
        Long count = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = database()"
                + " and table_name = 'recovery_settlement_receipts'", Long.class);
        return count != null && count > 0;
    }

    @Scheduled(initialDelayString="${storagehub.record-codes.initial-delay-ms:1000}",fixedDelayString="${storagehub.record-codes.delay-ms:30000}")
    @Transactional
    public void register() {
        Instant at=Instant.now();
        for(String table:List.of("renewals","support_tickets","rentals","return_cases","maintenance_tasks","refund_requests")) {
            String type=switch (table) { case "renewals" -> "GH"; case "rentals" -> "TH"; case "return_cases" -> "TK"; case "maintenance_tasks" -> "NV"; case "refund_requests" -> "HP"; default -> "HT"; };
            jdbc.query("select lower(bin_to_uuid(t.id)) id,t.created_at from "+table+" t where not exists(select 1 from business_record_codes c where c.resource_key=concat(?,lower(bin_to_uuid(t.id)))) order by t.created_at,t.id limit 100",
                (rs,i)->Map.entry(rs.getString(1),rs.getTimestamp(2).toInstant()),type+":")
                .forEach(row->codes.assign(type,type+":"+row.getKey(),row.getValue()));
        }
        var query=em.createQuery("select r.id from Rental r where r.status=:active and r.actualReturnedAt is null"+
            (cursor==null?"":" and r.id>:cursor")+" order by r.id",UUID.class).setParameter("active",RentalStatus.active).setMaxResults(100);
        if(cursor!=null)query.setParameter("cursor",cursor);
        var ids=query.getResultList();
        for(UUID id:ids) {
            var rental=em.find(Rental.class,id);
            var period=periods.read(rental);
            var termSource=terms.getIfAvailable();
            var term=termSource==null?Optional.<OverdueSources.Term>empty():termSource.term(rental,at);
            if(period.isPresent()&&term.isPresent()&&period.get().lastPermittedDate().equals(term.get().lastPermittedDate())
                &&at.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().isAfter(period.get().lastPermittedDate()))
                codes.assign("QH","RENTAL_TERM:"+id,at);
            var financial=finances.getIfAvailable();
            if(financial!=null)financial.read(rental,at).ifPresent(f->{for(var obligation:f.obligations())
                if(obligation.outstanding().signum()>0&&at.isAfter(obligation.dueAt()))
                    codes.assign("QH","PAYMENT_DUE:"+id+":"+obligation.id(),at);});
        }
        cursor=ids.isEmpty()?null:ids.getLast();
    }
}
