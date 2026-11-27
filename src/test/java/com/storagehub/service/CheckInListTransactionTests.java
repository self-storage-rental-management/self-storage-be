package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

class CheckInListTransactionTests {
    @Test
    void controllerListOverloadKeepsLazyResponseMappingInReadOnlyTransaction() throws Exception {
        var method = CheckInHandoverService.class.getMethod("list",
            ActorPrincipal.class, UUID.class, String.class, String.class,
            CheckInHandoverService.WorkGroup.class, Instant.class, Instant.class,
            int.class, int.class, String.class, String.class);
        var attribute = new AnnotationTransactionAttributeSource()
            .getTransactionAttribute(method, CheckInHandoverService.class);

        assertThat(attribute).as("controller list transaction boundary").isNotNull();
        assertThat(attribute.isReadOnly()).isTrue();
    }
}