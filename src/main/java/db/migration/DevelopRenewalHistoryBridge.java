package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import org.flywaydb.core.api.migration.*;

/** Applies a develop script omitted only because its version already belongs to a legacy feature. */
abstract class DevelopRenewalHistoryBridge extends BaseJavaMigration {
    private final String version,script,resource;
    protected DevelopRenewalHistoryBridge(String version,String script,String resource) {
        this.version=version;this.script=script;this.resource=resource;
    }
    private String sql() {
        try(var input=getClass().getResourceAsStream(resource)) {
            if(input==null)throw new IllegalStateException("Immutable develop migration missing");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Override public Integer getChecksum(){var crc=new java.util.zip.CRC32();crc.update(sql().getBytes(StandardCharsets.UTF_8));return (int)crc.getValue();}
    @Override public boolean canExecuteInTransaction(){return false;}
    @Override public void migrate(Context context)throws Exception {
        String table=context.getConfiguration().getTable();
        if(!table.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("Unsupported Flyway history table");
        try(var query=context.getConnection().prepareStatement("select script,success from `"+table+"` where version=?")) {
            query.setString(1,version);
            try(var rows=query.executeQuery()) {
                if(!rows.next()||!rows.getBoolean("success"))throw new IllegalStateException("Successful original migration required");
                if(script.equals(rows.getString("script")))return;
            }
        }
        execute(context.getConnection(),sql());
    }
    static void execute(Connection connection,String sql)throws Exception {
        for(String statement:sql.split(";"))if(!statement.isBlank())try(var query=connection.createStatement()){query.execute(statement);}
    }
}
