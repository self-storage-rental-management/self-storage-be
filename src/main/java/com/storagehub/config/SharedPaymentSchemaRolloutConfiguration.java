package com.storagehub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;

/** Explicit local rollout identity check before Flyway runs. */
@Configuration @Profile("shared-payment-schema")
public class SharedPaymentSchemaRolloutConfiguration {
    @Bean public FlywayMigrationStrategy sharedPaymentSchemaMigration(@Value("${storagehub.schema-rollout.expected-database}") String database) {
        return flyway->{RentalSupportSchemaRolloutConfiguration.verifyTarget(flyway.getConfiguration().getDataSource(),database);flyway.migrate();};
    }
}
