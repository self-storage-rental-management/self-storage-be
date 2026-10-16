package db.renewalcalendarmigration;

import org.flywaydb.core.api.migration.Context;

/** Fresh databases apply the calendar at a version distinct from develop SQL. */
public class V2026101410__ensure_staff_signing_calendar
    extends V2026101403__ensure_staff_signing_calendar {
    @Override public void migrate(Context context) throws Exception {
        String table=context.getConfiguration().getTable();
        if(!table.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("Unsupported Flyway history table");
        try(var query=context.getConnection().prepareStatement(
                "SELECT script,checksum,success FROM `"+table+"` WHERE version='2026101403'");
            var rows=query.executeQuery()) {
            if(rows.next() && "db.renewalcalendarmigration.V2026101403__ensure_staff_signing_calendar".equals(rows.getString("script"))) {
                if(!rows.getBoolean("success") || rows.getInt("checksum")!=getChecksum())
                    throw new IllegalStateException("Earlier calendar history differs; no repair performed");
                return;
            }
        }
        super.migrate(context);
    }
}
