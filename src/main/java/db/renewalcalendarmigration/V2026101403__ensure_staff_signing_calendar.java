package db.renewalcalendarmigration;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.migration.*;

public class V2026101403__ensure_staff_signing_calendar extends BaseJavaMigration {
    private String sql() {
        try(var input=getClass().getResourceAsStream("/db/migration-history/legacy-calendar-1301/V2026101301__create_staff_renewal_signing_calendar.sql")) {
            if(input==null)throw new IllegalStateException("Immutable calendar migration missing");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Override public Integer getChecksum(){var crc=new java.util.zip.CRC32();crc.update(sql().getBytes(StandardCharsets.UTF_8));return (int)crc.getValue();}
    @Override public boolean canExecuteInTransaction(){return false;}
    @Override public void migrate(Context context)throws Exception {
        String table=context.getConfiguration().getTable();
        if(!table.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("Unsupported Flyway history table");
        try(var query=context.getConnection().prepareStatement("select script,success from `"+table+"` where version='2026101301'" );var rows=query.executeQuery()) {
            if(rows.next()&&rows.getBoolean("success")&&"V2026101301__create_staff_renewal_signing_calendar.sql".equals(rows.getString("script")))return;
        }
        try(var query=context.getConnection().createStatement();var rows=query.executeQuery("select count(*) from information_schema.tables where table_schema=database() and table_name in ('renewal_calendar_staff_locks','renewal_signing_reservations')")) {
            if(rows.next()&&rows.getInt(1)!=0)throw new IllegalStateException("Calendar schema exists without matching history; no repair performed");
        }
        for(String statement:sql().split(";"))if(!statement.isBlank())try(var query=context.getConnection().createStatement()){query.execute(statement);}
    }
}
