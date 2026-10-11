package db.migration;

import com.storagehub.config.RentalSupportSchemaRolloutConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** New ledger version for develop; reuses only an exactly verified legacy schema. */
public class V2026101103__ensure_rental_ledger_and_notification_outbox extends BaseJavaMigration {
    private static final String SQL_RESOURCE="db/migration-history/legacy-1001/V2026101001__create_rental_ledger_and_notification_outbox.sql";
    private static final String SQL_HASH="0e7ad29986d1cfb6e5e5dd3380cc464067ef16e83a13a52b3d0eaf370426766f";
    private static final String SCHEMA_HASH="f933f9c259c8362e7795926dfea0edfc325c4396a965c46bb2530bcf995104af";
    private static final List<String> TABLES=List.of("rental_ledger_accounts","rental_ledger_allocations","rental_ledger_commands","rental_ledger_obligations","rental_ledger_receipts","rental_ledger_refunds","notification_outbox");
    @Override public Integer getChecksum(){return 2026101103;}
    @Override public boolean canExecuteInTransaction(){return false;}
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        String database;
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT DATABASE()")){rows.next();database=rows.getString(1);}
        RentalSupportSchemaRolloutConfiguration.verifyTarget(new SingleConnectionDataSource(connection,true),database);
        var resource=new ClassPathResource(SQL_RESOURCE);
        String sql;
        try(var stream=resource.getInputStream()){sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");}
        if(!SQL_HASH.equals(hash(sql)))throw new SQLException("Immutable ledger SQL resource differs; no ledger DDL performed");
        int count=0;
        for(String table:TABLES)try(var query=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")){
            query.setString(1,table);try(var rows=query.executeQuery()){rows.next();count+=rows.getInt(1);}
        }
        if(count>0){
            if(count!=7||!hasLegacyProof(connection,context.getConfiguration().getTable()))
                throw new SQLException("Ledger migration stopped: integration tables already exist without matching applied legacy history; no repair/reset performed");
            requireExactSchema(connection); // includes types, generated bridges, indexes, CHECK/FK definitions.
            return; // no DDL, no balance import, no history rewrite, no data update.
        }
        ScriptUtils.executeSqlScript(connection,resource);
        requireExactSchema(connection);
    }
    private static boolean hasLegacyProof(Connection connection,String history) throws SQLException {
        if(!history.matches("[A-Za-z0-9_]+"))throw new SQLException("Unsupported Flyway history table identifier");
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM `"+history+"` WHERE version='2026101001' AND script='V2026101001__create_rental_ledger_and_notification_outbox.sql' AND type='SQL' AND checksum=-1198625055 AND success=1")){rows.next();return rows.getInt(1)==1;}
    }
    private static void requireExactSchema(Connection connection) throws Exception {
        var schema=new StringBuilder();
        for(String table:TABLES)try(var statement=connection.createStatement();var rows=statement.executeQuery("SHOW CREATE TABLE `"+table+"`")){
            if(!rows.next())throw new SQLException("Ledger schema missing table; no repair performed");
            schema.append(rows.getString(1)).append('\t').append(rows.getString(2).replace("\r\n","\n")).append('\n');
        }
        if(!SCHEMA_HASH.equals(hash(schema.toString())))throw new SQLException("Ledger schema fingerprint differs from verified MySQL 8.4 definitions; no repair or existing-data change performed");
    }
    private static String hash(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
