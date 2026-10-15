package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import org.flywaydb.core.api.migration.*;

/** Applies a develop script omitted only because its version already belongs to a legacy feature. */
abstract class DevelopRenewalHistoryBridge extends BaseJavaMigration {
    private final String version,script,resource;
    private final String[] earlierIdentities;
    protected DevelopRenewalHistoryBridge(String version,String script,String resource,String... earlierIdentities) {
        if(earlierIdentities.length%2!=0)throw new IllegalArgumentException("Historical version/script pairs required");
        this.version=version;this.script=script;this.resource=resource;
        this.earlierIdentities=earlierIdentities.clone();
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
        // Local databases already applied the exact same SQL through the earlier
        // Java bridge. Confirm its script and checksum before treating this as done.
        for(int index=0;index<earlierIdentities.length;index+=2)try(var query=context.getConnection().prepareStatement(
                "select script,checksum,success from `"+table+"` where version=?")) {
            query.setString(1,earlierIdentities[index]);
            try(var rows=query.executeQuery()) {
                if(rows.next() && earlierIdentities[index+1].equals(rows.getString("script"))) {
                    if(!rows.getBoolean("success")||rows.getInt("checksum")!=getChecksum())
                        throw new IllegalStateException("Earlier bridge history differs; no repair performed");
                    return;
                }
            }
        }
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
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
            new org.springframework.core.io.support.EncodedResource(
                new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));
    }
}
