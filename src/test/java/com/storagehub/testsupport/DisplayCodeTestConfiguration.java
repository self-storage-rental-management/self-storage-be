package com.storagehub.testsupport;

import com.storagehub.service.BusinessRecordCodes;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static org.mockito.Mockito.mock;

/** Workflow tests isolate code allocation, which has its own database integration tests. */
@TestConfiguration(proxyBeanMethods=false)
public class DisplayCodeTestConfiguration {
    @Bean
    BusinessRecordCodes businessRecordCodes() {
        return mock(BusinessRecordCodes.class);
    }

    @Bean
    com.storagehub.service.rental.period.ConfirmedRentalPeriods confirmedRentalPeriods() {
        return mock(com.storagehub.service.rental.period.ConfirmedRentalPeriods.class);
    }
}
