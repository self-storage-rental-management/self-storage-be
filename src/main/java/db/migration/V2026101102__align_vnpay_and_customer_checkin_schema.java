package db.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive schema alignment. Never changes existing values or Flyway history. */
public class V2026101102__align_vnpay_and_customer_checkin_schema extends BaseJavaMigration {
    // Immutable version: create another migration for any future change.
    @Override public Integer getChecksum() { return 2026101102; }
    @Override public boolean canExecuteInTransaction() { return false; }

    private record Column(String table, String name, String type, Integer length, String ddl) {}
    private static final List<Column> COLUMNS = List.of(
        text("gateway_provider", 32), text("gateway_transaction_no", 100),
        text("gateway_bank_code", 32), text("gateway_card_type", 32),
        text("gateway_response_code", 16), text("gateway_transaction_status", 16),
        text("gateway_pay_date", 32),
        new Column("payments", "last_reconciled_at", "timestamp", null, "TIMESTAMP(6) NULL"),
        new Column("payments", "refunded_amount", "decimal", null, "DECIMAL(14,2) NOT NULL DEFAULT 0.00"),
        new Column("reservations", "appointment_at", "timestamp", null, "TIMESTAMP(6) NULL")
    );
    private static Column text(String name, int length) {
        return new Column("payments", name, "varchar", length, "VARCHAR(" + length + ") NULL");
    }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT VERSION()")) {
            if (!result.next() || !"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                || !result.getString(1).matches("8\\.4\\.[0-9]+(?:[-.].*)?")
                || result.getString(1).toLowerCase(Locale.ROOT).contains("tidb")) {
                throw new SQLException("Schema alignment requires verified MySQL 8.4; no alignment DDL performed");
            }
        }
        for (String table : List.of("payments", "reservations")) {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='id' AND data_type='binary' AND character_maximum_length=16")) {
                query.setString(1, table);
                try (var result = query.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new SQLException("Schema alignment requires " + table + ".id BINARY(16); no alignment DDL performed");
                    }
                }
            }
        }
        List<Column> missing = new ArrayList<>();
        // Validate ALL existing columns before the first ALTER; do not coerce incompatible data.
        for (Column column : COLUMNS) {
            try (var query = connection.prepareStatement("SELECT data_type,character_maximum_length,is_nullable,column_default,extra,numeric_precision,numeric_scale,datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
                query.setString(1, column.table()); query.setString(2, column.name());
                try (var result = query.executeQuery()) {
                    if (!result.next()) { missing.add(column); continue; }
                    boolean valid = column.type().equalsIgnoreCase(result.getString("data_type"))
                        && result.getString("extra").isBlank();
                    if (column.length() != null) valid &= result.getInt("character_maximum_length") == column.length();
                    if (column.type().equals("decimal")) {
                        String defaultValue = result.getString("column_default");
                        valid &= result.getInt("numeric_precision") == 14 && result.getInt("numeric_scale") == 2
                            && "NO".equals(result.getString("is_nullable")) && defaultValue != null;
                        if (defaultValue != null) {
                            try { valid &= new BigDecimal(defaultValue).compareTo(BigDecimal.ZERO) == 0; }
                            catch (NumberFormatException invalidDefault) { valid = false; }
                        }
                    } else {
                        valid &= "YES".equals(result.getString("is_nullable")) && result.getString("column_default") == null;
                        if (column.type().equals("timestamp")) valid &= result.getInt("datetime_precision") == 6;
                    }
                    if (!valid) throw new SQLException("Incompatible " + column.table() + "." + column.name()
                        + "; reconcile schema without modifying existing values; no alignment DDL performed");
                }
            }
        }
        for (String table : List.of("payments", "reservations")) {
            var clauses = missing.stream().filter(column -> table.equals(column.table()))
                .map(column -> "ADD COLUMN " + column.name() + " " + column.ddl()).toList();
            if (!clauses.isEmpty()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE " + table + " " + String.join(", ", clauses));
                }
            }
        }
    }
}
