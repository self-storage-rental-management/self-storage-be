package com.storagehub.api.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.CentralizedBusinessPolicyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CentralizedBusinessPolicyControllerTests {

    private ActorContext actorContext;
    private CentralizedBusinessPolicyService service;
    private CentralizedBusinessPolicyController controller;
    private ActorPrincipal businessActor;

    @BeforeEach
    void setUp() {
        actorContext = mock(ActorContext.class);
        service = mock(CentralizedBusinessPolicyService.class);
        controller = new CentralizedBusinessPolicyController(actorContext, service);

        businessActor = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of("policies:read", "policies:update"),
            Map.of()
        );
        when(actorContext.required()).thenReturn(businessActor);
    }

    private CentralizedPolicyResponse dummyResponse(String version, PublicationStatus status, boolean active) {
        return new CentralizedPolicyResponse(
            UUID.randomUUID(),
            version,
            status,
            "ACTIVE",
            Instant.now(),
            businessActor.userId(),
            "Business Manager",
            LocalDate.of(2026, 1, 1),
            null,
            active,
            "Test summary",
            true,
            "MONTHLY_RENT_MULTIPLE",
            BigDecimal.valueOf(1.0),
            new BigDecimal("2000000.00"),
            new BigDecimal("0.2000"),
            new BigDecimal("500000.00"),
            "Full refund rules",
            "Partial refund rules",
            "No refund rules",
            7,
            true,
            List.of(1, 3, 6, 12, 24),
            15,
            true,
            new BigDecimal("0.0500"),
            true,
            14,
            BigDecimal.ONE,
            1,
            new BigDecimal("500000.00"),
            3,
            "FIXED_DAILY_AMOUNT",
            new BigDecimal("50000.00"),
            new BigDecimal("0.0050"),
            7,
            "[]",
            true,
            true,
            true,
            true,
            "PRICE-2026.1",
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    void testGetActivePolicy() {
        when(service.getActivePolicy()).thenReturn(dummyResponse("POL-2026.1", PublicationStatus.PUBLISHED, true));

        ApiResponse<CentralizedPolicyResponse> response = controller.getActivePolicy();
        assertThat(response).isNotNull();
        assertThat(response.data().policyVersion()).isEqualTo("POL-2026.1");
        assertThat(response.data().active()).isTrue();
    }

    @Test
    void testGetDraftPolicy() {
        when(service.getDraftOrCreate(businessActor)).thenReturn(dummyResponse("POL-2026.2", PublicationStatus.DRAFT, false));

        ApiResponse<CentralizedPolicyResponse> response = controller.getDraft();
        assertThat(response).isNotNull();
        assertThat(response.data().policyVersion()).isEqualTo("POL-2026.2");
        assertThat(response.data().publicationStatus()).isEqualTo(PublicationStatus.DRAFT);
    }

    @Test
    void testPublishPolicy() {
        PublishCentralizedPolicyRequest req = new PublishCentralizedPolicyRequest(
            LocalDate.now(), null, "Publish summary"
        );
        UUID draftId = UUID.randomUUID();
        when(service.publishPolicy(businessActor, draftId, req))
            .thenReturn(dummyResponse("POL-2026.2", PublicationStatus.PUBLISHED, true));

        ApiResponse<CentralizedPolicyResponse> response = controller.publishPolicy(draftId, req);
        assertThat(response).isNotNull();
        assertThat(response.data().publicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(response.data().active()).isTrue();
    }

    @Test
    void testCustomerAndFacilityEndpointsReturnActivePolicy() {
        when(service.getActivePolicy()).thenReturn(dummyResponse("POL-2026.1", PublicationStatus.PUBLISHED, true));

        ApiResponse<CentralizedPolicyResponse> customerRes = controller.getCustomerActivePolicy();
        assertThat(customerRes.data().policyVersion()).isEqualTo("POL-2026.1");

        ApiResponse<CentralizedPolicyResponse> facilityRes = controller.getFacilityActivePolicy();
        assertThat(facilityRes.data().policyVersion()).isEqualTo("POL-2026.1");
    }
}
