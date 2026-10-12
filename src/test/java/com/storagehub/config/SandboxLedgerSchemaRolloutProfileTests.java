package com.storagehub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class SandboxLedgerSchemaRolloutProfileTests {
    @Test void rolloutIsExplicitAndLeavesRuntimePaymentCreditDisabled() throws Exception {
        assertThat(SandboxLedgerSchemaRolloutConfiguration.class.getAnnotation(Profile.class).value())
            .containsExactly("sandbox-ledger-schema");
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application-sandbox-ledger-schema.properties")) {
            properties.load(input);
        }
        assertThat(properties.getProperty("spring.flyway.locations"))
            .isEqualTo("classpath:db/migration,classpath:db/paymentmigration,classpath:db/ledger-sandbox-migration");
        assertThat(properties.getProperty("storagehub.schema-rollout.expected-database"))
            .isEqualTo("${STORAGEHUB_SCHEMA_ROLLOUT_DATABASE}");
        assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${STORAGEHUB_DB_URL}");
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        for (String key : new String[]{"spring.flyway.baseline-on-migrate","spring.flyway.out-of-order",
            "storagehub.integration.ledger.enabled","storagehub.integration.sandbox-reconciliation.enabled",
            "app.bootstrap-demo-data.enabled"}) assertThat(properties.getProperty(key)).isEqualTo("false");
        assertThat(properties.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
    }
}
