package com.storagehub.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {
    private com.storagehub.domain.model.PaymentSimulationOutcome simulationOutcome =
        com.storagehub.domain.model.PaymentSimulationOutcome.SUCCESS;
}
