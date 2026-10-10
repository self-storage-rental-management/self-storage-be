package com.storagehub.api.renewal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.*;
import com.storagehub.service.renewal.operations.RenewalOperationService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MVC contract checks, not live JWT/DB/E2E tests. */
class RenewalExceptionProposalControllerTests {
    RenewalOperationService service;ActorContext context;ActorPrincipal actor;MockMvc mvc;UUID id;String route;
    @BeforeEach void setup(){
        service=mock(RenewalOperationService.class);context=mock(ActorContext.class);id=UUID.randomUUID();
        actor=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
        when(context.required()).thenReturn(actor);route="/api/customer/renewals/"+id+"/exception-proposal";
        mvc=MockMvcBuilders.standaloneSetup(new CustomerRenewalOperationController(context,service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void getReturnsPublicEnvelopeOnly() throws Exception {
        Instant now=Instant.parse("2026-10-09T03:00:00Z");
        when(service.exceptionProposal(actor,id)).thenReturn(new RenewalExceptionProposalResponse(id,2L,UUID.randomUUID(),"AVAILABLE",now,
            now,now.plusSeconds(1000),now.plusSeconds(7200),now.plusSeconds(3600),now.plusSeconds(5400),now.plusSeconds(10800),now.plusSeconds(3600),true,List.of()));
        mvc.perform(get(route)).andExpect(status().isOk()).andExpect(jsonPath("data.renewalId").value(id.toString()))
            .andExpect(jsonPath("data.expectedVersion").value(2)).andExpect(jsonPath("data.confirmationAllowed").value(true))
            .andExpect(jsonPath("data.incidentId").doesNotExist()).andExpect(jsonPath("data.reason").doesNotExist())
            .andExpect(jsonPath("data.evidenceFileIds").doesNotExist()).andExpect(jsonPath("data.actorId").doesNotExist());
    }
    @Test void invalidOrRepeatedQueryDoesNotReachService() throws Exception {
        mvc.perform(get(route).param("decisionRef",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("page","0","1")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void ownershipAndUnavailableSourceErrorsAreNotConvertedToSuccess() throws Exception {
        when(service.exceptionProposal(actor,id)).thenThrow(ApiExceptions.notFound("Renewal not found"));mvc.perform(get(route)).andExpect(status().isNotFound());
        doThrow(ApiExceptions.conflict("DEFERRED_SOURCE: policy missing")).when(service).exceptionProposal(actor,id);mvc.perform(get(route)).andExpect(status().isConflict());
    }
    @Test void unauthenticatedActorIsDenied() throws Exception {
        when(context.required()).thenThrow(ApiExceptions.unauthorized("Authentication required"));mvc.perform(get(route)).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void internalTimelineIsNotAddedToCustomerController() throws Exception {
        // Mapping boundary only. Shared GlobalExceptionHandler currently turns a missing handler into 500;
        // that external defect is reported separately, not repaired by exposing a Customer incident route.
        var routeOnly=MockMvcBuilders.standaloneSetup(new CustomerRenewalOperationController(context,service)).build();
        routeOnly.perform(get("/api/customer/renewals/"+id+"/facility-incidents")).andExpect(status().isNotFound());verifyNoInteractions(service);
    }
}
