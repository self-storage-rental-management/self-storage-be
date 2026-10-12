package com.storagehub.service.ledger;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail startup when gateway credit is enabled against an incomplete MySQL schema. Read-only. */
@Component
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
public class SandboxGatewaySchemaGuard implements InitializingBean {
    private final JdbcTemplate jdbc;
    public SandboxGatewaySchemaGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void afterPropertiesSet() {
        String product;
        try (var connection = jdbc.getDataSource().getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("Cannot inspect Sandbox ledger datasource", error);
        }
        if (!"MySQL".equalsIgnoreCase(product))
            throw new IllegalStateException("Sandbox ledger schema has only been certified on MySQL");
        require("gateway evidence table", count("select count(*) from information_schema.tables where table_schema=database() and table_name='rental_ledger_gateway_evidence'") == 1);
        require("nullable gateway receipt file", "YES".equals(jdbc.queryForObject(
            "select is_nullable from information_schema.columns where table_schema=database() and table_name='rental_ledger_receipts' and column_name='evidence_file_id'", String.class)));
        for (String column : List.of("receipt_id","payment_id","proof_log_id","transaction_no"))
            require("unique gateway "+column, count("select count(*) from (select index_name from information_schema.statistics where table_schema=database() and table_name='rental_ledger_gateway_evidence' group by index_name having count(*)=1 and sum(case when column_name=? and non_unique=0 then 1 else 0 end)=1) indexes",column) == 1);
        for (var binding : List.of(new String[]{"receipt_id","rental_ledger_receipts"},
            new String[]{"payment_uuid","payments"},new String[]{"proof_uuid","activity_logs"}))
            require("gateway foreign key "+binding[0], count("select count(*) from information_schema.key_column_usage where table_schema=database() and table_name='rental_ledger_gateway_evidence' and column_name=? and referenced_table_name=? and referenced_column_name='id'",binding[0],binding[1]) == 1);
        var receiptChecks = jdbc.queryForList("select lower(cc.check_clause) from information_schema.table_constraints tc join information_schema.check_constraints cc on cc.constraint_schema=tc.constraint_schema and cc.constraint_name=tc.constraint_name where tc.table_schema=database() and tc.table_name='rental_ledger_receipts' and tc.constraint_type='CHECK'",String.class);
        require("Sandbox receipt method check", receiptChecks.stream().anyMatch(c -> c.contains("vnpay_sandbox") && c.contains("method")));
        require("gateway/document evidence check", receiptChecks.stream().anyMatch(c -> c.contains("vnpay_sandbox") && c.contains("evidence_file_id") && c.contains("evidence_file_uuid") && c.contains("actor_uuid")));
        var gatewayChecks = jdbc.queryForList("select lower(cc.check_clause) from information_schema.table_constraints tc join information_schema.check_constraints cc on cc.constraint_schema=tc.constraint_schema and cc.constraint_name=tc.constraint_name where tc.table_schema=database() and tc.table_name='rental_ledger_gateway_evidence' and tc.constraint_type='CHECK'",String.class);
        require("Sandbox-only evidence check",gatewayChecks.stream().anyMatch(c -> c.contains("environment") && c.contains("sandbox")));
    }

    private long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    private static void require(String property,boolean valid) {
        if (!valid) throw new IllegalStateException("Sandbox ledger schema incomplete: "+property);
    }
}
