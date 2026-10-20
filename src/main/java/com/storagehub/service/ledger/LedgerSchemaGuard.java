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
    private final boolean gateway;
    public LedgerSchemaGuard(JdbcTemplate jdbc){this(jdbc,false);}
    @org.springframework.beans.factory.annotation.Autowired
    public LedgerSchemaGuard(JdbcTemplate jdbc,@org.springframework.beans.factory.annotation.Value("${storagehub.integration.sandbox-reconciliation.enabled:false}") boolean gateway){this.jdbc=jdbc;this.gateway=gateway;}
    public void afterPropertiesSet(){
        for(String query:new String[]{
            "select rental_id,customer_id,facility_id,revision from rental_ledger_accounts where 1=0",
            "select id,rental_id,renewal_id,kind,amount,due_at_ms,source_ref from rental_ledger_obligations where 1=0",
            "select id,rental_id,method,amount,evidence_ref,evidence_file_id,actor_id,received_at_ms from rental_ledger_receipts where 1=0",
            "select receipt_id,obligation_id,rental_id,amount from rental_ledger_allocations where 1=0",
            "select id,rental_id,receipt_id,obligation_id,amount,status,decision_ref,payout_ref from rental_ledger_refunds where 1=0",
            "select actor_id,operation,key_hash,rental_id,payload_hash,result_id from rental_ledger_commands where 1=0"})jdbc.queryForList(query);
        jdbc.queryForList("select obligation_id,due_at_ms from rental_ledger_due_changes where 1=0");
        jdbc.queryForList("select return_id,ledger_revision,settled_at_ms from return_financial_snapshots where 1=0");
        jdbc.queryForList("select return_id,obligation_id,amount from return_ledger_allocations where 1=0");
        if(gateway)jdbc.queryForList("select receipt_id,payment_id,proof_log_id,environment,transaction_no from rental_ledger_gateway_evidence where 1=0");
    }
}
