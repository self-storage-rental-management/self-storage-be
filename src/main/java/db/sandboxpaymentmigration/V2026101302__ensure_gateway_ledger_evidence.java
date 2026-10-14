package db.sandboxpaymentmigration;

import org.flywaydb.core.api.migration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.storagehub.service.ledger.SandboxGatewaySchemaGuard;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/** Additive opt-in rollout with a new version; never rewrites historical 1202 entries. */
public class V2026101302__ensure_gateway_ledger_evidence extends BaseJavaMigration {
    private static String script() {
        try(var input=V2026101302__ensure_gateway_ledger_evidence.class.getResourceAsStream("/db/ledger-sandbox-migration/V2026101202__sandbox_gateway_ledger_evidence.sql")) {
            if(input==null)throw new IllegalStateException("Immutable Sandbox migration resource missing");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Override public Integer getChecksum(){var crc=new java.util.zip.CRC32();crc.update(script().getBytes(StandardCharsets.UTF_8));return (int)crc.getValue();}
    @Override public boolean canExecuteInTransaction(){return false;}
    @Override public void migrate(Context context)throws Exception {
        var connection=context.getConnection();
        if(!"MySQL".equals(connection.getMetaData().getDatabaseProductName())||!connection.getMetaData().getDatabaseProductVersion().startsWith("8.4"))
            throw new IllegalStateException("Gateway ledger rollout requires MySQL 8.4");
        var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        Long exists=jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='rental_ledger_gateway_evidence'",Long.class);
        if(exists==null)throw new IllegalStateException("Cannot inspect gateway schema");
        if(exists==0) {
            Long invalid=jdbc.queryForObject("select count(*) from rental_ledger_receipts where method not in ('CASH','VERIFIED_BANK','SIMULATED','VNPAY_SANDBOX') or actor_uuid is null or (method<>'VNPAY_SANDBOX' and evidence_file_uuid is null)",Long.class);
            if(invalid==null||invalid!=0)throw new IllegalStateException("Existing receipt data requires reconciliation before gateway rollout");
            for(String sql:script().split(";"))if(!sql.isBlank())try(var statement=connection.createStatement()){statement.execute(sql);}
        }
        new SandboxGatewaySchemaGuard(jdbc).afterPropertiesSet();
    }
}
