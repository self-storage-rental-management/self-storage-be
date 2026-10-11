package com.storagehub.service.ledger;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail startup when explicitly enabled without reviewed schema. Never runs DDL or repairs DB. */
@Component
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
public class LedgerSchemaGuard implements InitializingBean {
    private final JdbcTemplate jdbc;
    public LedgerSchemaGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void afterPropertiesSet(){
        for(String query:new String[]{
            "select rental_id,customer_id,facility_id,revision from duong_ledger_accounts where 1=0",
            "select id,rental_id,renewal_id,kind,amount,due_at_ms,source_ref from duong_ledger_obligations where 1=0",
            "select id,rental_id,method,amount,evidence_ref,evidence_file_id,actor_id,received_at_ms from duong_ledger_receipts where 1=0",
            "select receipt_id,obligation_id,rental_id,amount from duong_ledger_allocations where 1=0",
            "select id,rental_id,receipt_id,obligation_id,amount,status,decision_ref,payout_ref from duong_ledger_refunds where 1=0",
            "select actor_id,operation,key_hash,rental_id,payload_hash,result_id from duong_ledger_commands where 1=0"})jdbc.queryForList(query);
    }
}
