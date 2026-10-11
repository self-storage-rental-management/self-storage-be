package db.paymentmigration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Opt-in only. Legacy identifiers are NOT proof of payment or gateway reconciliation. */
public class V2026101101__add_payment_gateway_intent_id extends BaseJavaMigration {
    @Override public boolean canExecuteInTransaction() { return false; }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("select version()")) {
            if (!result.next() || !"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                || !result.getString(1).matches("8\\.4\\.[0-9]+(?:[-.].*)?")
                || result.getString(1).toLowerCase(Locale.ROOT).contains("tidb")) {
                throw new SQLException("Payment migration requires separately verified MySQL 8.4; no changes performed");
            }
        }
        if (count(connection, "select count(*) from information_schema.columns where table_schema=database() and table_name='payments' and column_name='id' and data_type='binary' and character_maximum_length=16") != 1) {
            throw new SQLException("Payment migration requires payments.id BINARY(16); reconcile schema first");
        }
        boolean exists = false;
        boolean nullable = true;
        String characterDefinition = "";
        try (var statement = connection.createStatement(); var column = statement.executeQuery(
            "select data_type,character_maximum_length,is_nullable,character_set_name,collation_name,column_default,extra from information_schema.columns where table_schema=database() and table_name='payments' and column_name='gateway_intent_id'")) {
            if (column.next()) {
                exists = true;
                if (!"varchar".equals(column.getString(1)) || column.getLong(2) != 100
                    || column.getString(6) != null || !column.getString(7).isBlank()) {
                    throw new SQLException("Existing payment gateway column differs from expected VARCHAR(100); reconcile without truncating evidence");
                }
                nullable = "YES".equals(column.getString(3));
                String charset = column.getString(4), collation = column.getString(5);
                if (charset == null || collation == null || !charset.matches("[a-zA-Z0-9_]+") || !collation.matches("[a-zA-Z0-9_]+")) {
                    throw new SQLException("Payment column character metadata cannot be preserved safely");
                }
                characterDefinition = " CHARACTER SET " + charset + " COLLATE " + collation;
            }
        }
        boolean unique = count(connection,
            "select count(*) from (select index_name from information_schema.statistics where table_schema=database() and table_name='payments' and non_unique=0 group by index_name having count(*)=1 and max(column_name='gateway_intent_id' and sub_part is null)=1) indexes_found") > 0;
        if (!unique && count(connection, "select count(*) from information_schema.statistics where table_schema=database() and table_name='payments' and index_name='uk_payments_gateway_intent_id'") > 0) {
            throw new SQLException("Payment gateway unique-index name is already used by a different index; reconcile first");
        }
        // Check effective identifiers before ALTER/UPDATE. Never replace duplicate real/simulated references.
        if (exists && count(connection,
            "select count(*) from (select case when gateway_intent_id is null or trim(gateway_intent_id)='' then concat('LEGACY-UNVERIFIED-',lower(hex(id))) else gateway_intent_id end as effective_id from payments group by effective_id having count(*)>1) collisions") > 0) {
            throw new SQLException("Payment gateway identifiers collide; no changes performed, reconcile existing evidence first");
        }
        if (!exists) {
            execute(connection, "alter table payments add column gateway_intent_id varchar(100) null");
        }
        execute(connection, "update payments set gateway_intent_id=concat('LEGACY-UNVERIFIED-',lower(hex(id))) where gateway_intent_id is null or trim(gateway_intent_id)=''");
        if (nullable) {
            execute(connection, "alter table payments modify column gateway_intent_id varchar(100)" + characterDefinition + " not null");
        }
        if (!unique) {
            execute(connection, "alter table payments add constraint uk_payments_gateway_intent_id unique (gateway_intent_id)");
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Payment migration preflight returned no result");
            return result.getLong(1);
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
