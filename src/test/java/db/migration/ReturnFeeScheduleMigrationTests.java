package db.migration;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ReturnFeeScheduleMigrationTests {
    @Test
    void migrationImportsConfiguredBaselineAndLeavesCompletedAmountsUntouched() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE system_settings (setting_key VARCHAR(100), setting_value VARCHAR(2000))");
            sql.execute("INSERT INTO system_settings VALUES ('returnFeeSchedule', '{\"revision\":8,\"prices\":{\"minor\":123456}}')");
            sql.execute("CREATE TABLE return_cases (status VARCHAR(40), damage_fee DECIMAL(19,2))");
            sql.execute("INSERT INTO return_cases VALUES ('completed', 432100)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration-history/november/V2026112711__immutable_return_fee_schedule/V2026112711__immutable_return_fee_schedule.sql"));
            try (var rows = sql.executeQuery("SELECT * FROM return_fee_schedule_versions")) {
                assertTrue(rows.next()); assertEquals(0, rows.getLong("revision"));
                assertTrue(rows.getString("payload").contains("123456"));
                assertNull(rows.getTimestamp("published_at")); assertFalse(rows.next());
            }
            try (var rows = sql.executeQuery("SELECT damage_fee FROM return_cases WHERE status='completed'")) {
                assertTrue(rows.next()); assertEquals(432100, rows.getInt(1));
            }
        }
    }

    @Test
    void migrationSeedsLockableBaselineEvenWithoutLegacySetting() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE system_settings (setting_key VARCHAR(100), setting_value VARCHAR(2000))");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration-history/november/V2026112711__immutable_return_fee_schedule/V2026112711__immutable_return_fee_schedule.sql"));
            try (var rows = sql.executeQuery("SELECT * FROM return_fee_schedule_versions WHERE revision=0 FOR UPDATE")) {
                assertTrue(rows.next()); assertNull(rows.getString("payload"));
            }
        }
    }
}
