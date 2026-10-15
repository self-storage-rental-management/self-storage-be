package com.storagehub.config;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.jdbc.StatementInterceptor;
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl;

/** Resolves an already-applied Java migration after its implementation moved to a new version. */
final class HistoricalReconciliationMigration implements JavaMigration {
    private final int number;

    HistoricalReconciliationMigration(int number) {
        if(number!=1 && number!=2 && number!=5 && number!=6)
            throw new IllegalArgumentException("Unsupported historical reconciliation version");
        this.number=number;
    }
    private boolean packageCleanup() { return number==1 || number==5; }

    @Override public MigrationVersion getVersion() { return MigrationVersion.fromVersion("202610140"+number); }
    @Override public String getDescription() {
        return packageCleanup() ? "reconcile develop package cleanup" : "reconcile develop facility details";
    }
    @Override public Integer getChecksum() {
        String resource=packageCleanup()
            ? "/db/migration-history/develop-1301/V2026101301__deactivate_duplicate_effective_rental_packages.sql"
            : "/db/migration-history/develop-1302/V2026101302__add_facility_operating_details.sql";
        try(var input=getClass().getResourceAsStream(resource)) {
            if(input==null)throw new IllegalStateException("Historical migration resource missing");
            var crc=new CRC32();
            crc.update(new String(input.readAllBytes(),StandardCharsets.UTF_8)
                .replace("\r\n","\n").getBytes(StandardCharsets.UTF_8));
            return (int)crc.getValue();
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Override public boolean canExecuteInTransaction() { return false; }
    @Override public void migrate(Context context) {
        throw new IllegalStateException("Historical migration is validation-only and must already be applied");
    }
    @Override public ResolvedMigration getResolvedMigration(Configuration config, StatementInterceptor interceptor) {
        var base=JavaMigration.super.getResolvedMigration(config,interceptor);
        String script="db.migration.V202610140"+number+"__reconcile_develop_"
            +(packageCleanup()?"package_cleanup":"facility_details");
        return new ResolvedMigrationImpl(base.getVersion(),base.getDescription(),script,
            base.getChecksum(),null,base.getType(),base.getPhysicalLocation(),base.getExecutor());
    }
}
