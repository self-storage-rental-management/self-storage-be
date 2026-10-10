package com.storagehub.service.renewal.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storagehub.api.renewal.*;
import com.storagehub.api.renewal.RenewalOperationCommands.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static com.storagehub.service.renewal.operations.RenewalOperationSources.*;

/** Pure unit tests. No datasource/schema/bootstrap/payment engine is started. */
class RenewalExceptionProposalTests {
    static final Instant NOW=Instant.parse("2026-10-09T03:00:00Z");
    EntityManager em;RenewalPersistence store;RenewalOperationService service;ObjectMapper mapper;
    Clock clock;ObjectProvider<Clock> clocks;
    ObjectProvider<PolicySource> policies;ObjectProvider<CalendarSource> calendars;ObjectProvider<SafetySource> safeties;
    PolicySource policy;CalendarSource calendar;SafetySource safety;
    Renewal n;RenewalWorkflow workflow;RenewalOperationState state;User customer;ActorPrincipal actor;
    RenewalOperationEvent decisionEvent;ExceptionDecision decision;
    Map<String,Object> entities;
    static String key(Class<?> type,Object id){return type.getName()+":"+id;}
    <T>T id(T entity){ReflectionTestUtils.setField(entity,"id",UUID.randomUUID());return entity;}
    void put(Object entity,UUID id){entities.put(key(entity.getClass(),id),entity);}
    @SuppressWarnings("unchecked") <T> ObjectProvider<T> provider(T value){var p=(ObjectProvider<T>)mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);return p;}
    @BeforeEach void setup() throws Exception {
        em=mock(EntityManager.class);store=mock(RenewalPersistence.class);entities=new HashMap<>();
        mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        clock=Clock.fixed(NOW,ZoneOffset.UTC);clocks=provider(clock);
        policy=mock(PolicySource.class);calendar=mock(CalendarSource.class);safety=mock(SafetySource.class);
        policies=provider(policy);calendars=provider(calendar);safeties=provider(safety);
        service=new RenewalOperationService(em,mapper,store,mock(AuditLogService.class),clocks,
            provider(null),policies,calendars,safeties,provider(null),provider(null),provider(null));
        ReflectionTestUtils.setField(service,"assignmentSources",provider(null));
        customer=id(new User());var facility=id(new Facility());var type=id(new UnitType());
        var unit=id(new StorageUnit());unit.setFacility(facility);unit.setUnitType(type);
        var rental=id(new Rental());rental.setCustomer(customer);rental.setFacility(facility);rental.setStorageUnit(unit);
        rental.setStartDate(LocalDate.of(2026,9,1));rental.setContractEndDate(LocalDate.of(2026,10,8));
        var periods=mock(com.storagehub.service.rental.period.RentalPeriodResolver.class);
        when(periods.require(rental)).thenAnswer(a -> com.storagehub.service.rental.period.RentalPeriod.verified(rental.getId(),rental.getStartDate(),rental.getContractEndDate(),com.storagehub.service.rental.period.RentalPeriod.Convention.INCLUSIVE,"TEST-ONLY-evidence").orElseThrow());
        ReflectionTestUtils.setField(service,"periods",periods);
        n=id(new Renewal());n.setRental(rental);n.setRequestedBy(customer);n.setStatus(RenewalStatus.appointment_scheduled);
        n.setNewEndDate(LocalDate.of(2026,11,8));n.setAmount(new BigDecimal("100"));
        var terms=new RenewalQuoteResponse.Terms(rental.getContractEndDate(),LocalDate.of(2026,10,9),LocalDate.of(2026,11,9),n.getNewEndDate(),
            type.getId(),"TEST",UUID.randomUUID(),"TEST-v1",1,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),BigDecimal.ZERO,
            new BigDecimal("100"),new BigDecimal("20"),new BigDecimal("80"),"VND","TEST-policy","TEST-v1",unit.getId(),facility.getId());
        var quote=id(new RenewalQuote(rental,customer,mapper.writeValueAsString(terms),"TEST-hash",NOW.minusSeconds(1800),NOW.plusSeconds(1800)));
        var rev=id(new RenewalAcceptedRevision(n,quote,1,NOW.minusSeconds(1000),"INTERNAL_ACCEPTANCE_NOTE"));
        workflow=new RenewalWorkflow(n,rev);ReflectionTestUtils.setField(workflow,"version",3L);
        workflow.reviewed(customer,NOW.minusSeconds(900),"INTERNAL_MANAGER_REASON",NOW.plusSeconds(300),UUID.randomUUID());
        state=new RenewalOperationState(n);state.deposit(UUID.randomUUID(),NOW.minusSeconds(600),NOW.plusSeconds(7200),NOW.plusSeconds(14400),"TEST","TEST-v1");
        state.appointment(UUID.randomUUID(),NOW.plusSeconds(900),NOW.plusSeconds(1800));
        var incident=id(new RenewalOperationEvent(n,"INCIDENT",NOW.minusSeconds(100),UUID.randomUUID(),"{}"));put(incident,incident.getId());
        decision=new ExceptionDecision(incident.getId(),ExceptionAction.APPROVE_RESCHEDULE_BEFORE_CUTOFF,NOW.plusSeconds(3600),NOW.plusSeconds(10800),
            "SECRET_INTERNAL_FAULT_REASON",List.of(UUID.randomUUID()),3L);
        decisionEvent=id(new RenewalOperationEvent(n,"EXCEPTION",NOW.minusSeconds(30),UUID.randomUUID(),mapper.writeValueAsString(decision)));put(decisionEvent,decisionEvent.getId());
        state.proposedException(decisionEvent.getId());put(n,n.getId());put(workflow,n.getId());put(state,n.getId());
        when(em.find(any(),any())).thenAnswer(i->entities.get(key(i.getArgument(0),i.getArgument(1))));
        when(em.find(User.class,customer.getId(),LockModeType.PESSIMISTIC_WRITE)).thenReturn(customer);
        when(store.lockRental(rental.getId())).thenReturn(rental);
        when(store.lockWorkflow(n.getId(),3L)).thenReturn(workflow);
        when(store.replay(any(),anyString(),anyString(),any(),any())).thenReturn(Optional.empty());
        when(store.canonical(any())).thenAnswer(i->mapper.writeValueAsString(i.getArgument(0)));
        doAnswer(i->{Object e=i.getArgument(0);if(e instanceof RenewalOperationEvent event){id(event);put(event,event.getId());}return null;}).when(em).persist(any());
        doAnswer(i->{ReflectionTestUtils.setField(workflow,"version",workflow.getVersion()+1);return null;}).when(em).lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        when(policy.read(n)).thenReturn(Optional.of(new Policy("TEST-BO","TEST-v1",Duration.ofHours(2),Duration.ofHours(2))));
        when(calendar.atomic()).thenReturn(true);when(safety.atomic()).thenReturn(true);
        when(safety.check(eq(n),any(),any(),any())).thenReturn(NOW.plusSeconds(14400));
        when(calendar.slot(n,decision.appointmentAt())).thenReturn(Optional.of(new Slot("TEST-slot",decision.appointmentAt(),decision.appointmentAt().plusSeconds(1800))));
        actor=new ActorPrincipal(customer.getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
    }
    @Test void projectionIsCurrentAndRedactsAllInternalData() throws Exception {
        var result=service.exceptionProposal(actor,n.getId());
        assertThat(result.status()).isEqualTo("AVAILABLE");assertThat(result.confirmationAllowed()).isTrue();
        assertThat(result.decisionRef()).isEqualTo(decisionEvent.getId());assertThat(result.expectedVersion()).isEqualTo(3L);
        assertThat(result.proposedAppointmentStart()).isEqualTo(decision.appointmentAt());assertThat(result.validUntil()).isEqualTo(decision.appointmentAt());
        String json=mapper.writeValueAsString(result);
        assertThat(json).doesNotContain("SECRET_INTERNAL", "incidentId", "actorId", "evidenceFileIds", "reviewer", "reason");
        verify(em,never()).persist(any());verify(safety,never()).retain(any(),any(),any());verify(calendar,never()).reserve(any(),any(),any());
    }
    @Test void noProposalIsAnHonestEmptyState(){ReflectionTestUtils.setField(state,"pendingExceptionRef",null);var r=service.exceptionProposal(actor,n.getId());assertThat(r.status()).isEqualTo("NONE");assertThat(r.decisionRef()).isNull();assertThat(r.confirmationAllowed()).isFalse();}
    @Test void missingSourcesNeverMeanReady(){when(safeties.getIfAvailable()).thenReturn(null);var r=service.exceptionProposal(actor,n.getId());assertThat(r.confirmationAllowed()).isFalse();assertThat(r.disabledReasons()).containsExactly("SOURCE_UNAVAILABLE");assertThat(r.proposedAppointmentStart()).isEqualTo(decision.appointmentAt());}
    @Test void missingPublishedPolicyFailsClosed(){when(policy.read(n)).thenReturn(Optional.empty());assertThatThrownBy(()->service.exceptionProposal(actor,n.getId())).hasMessageContaining("DEFERRED_SOURCE");}
    @Test void unavailableCalendarFailsClosed(){when(calendar.slot(n,decision.appointmentAt())).thenReturn(Optional.empty());assertThatThrownBy(()->service.exceptionProposal(actor,n.getId())).hasMessageContaining("slot unavailable");}
    @Test void proposalPastItsAppointmentExpires(){when(clocks.getIfAvailable()).thenReturn(Clock.fixed(decision.appointmentAt(),ZoneOffset.UTC));var r=service.exceptionProposal(actor,n.getId());assertThat(r.confirmationAllowed()).isFalse();assertThat(r.disabledReasons()).containsExactly("PROPOSAL_EXPIRED");}
    @Test void expiredCutoffIsTheDisplayedExpiryEvenWhenAppointmentIsLater(){Instant cutoff=NOW.minusSeconds(1);ReflectionTestUtils.setField(state,"recoveryCutoff",cutoff);var r=service.exceptionProposal(actor,n.getId());assertThat(r.confirmationAllowed()).isFalse();assertThat(r.disabledReasons()).containsExactly("PROPOSAL_EXPIRED");assertThat(r.validUntil()).isEqualTo(cutoff);}
    @Test void missingCutoffIsUnknownRatherThanExpired(){ReflectionTestUtils.setField(state,"recoveryCutoff",null);var r=service.exceptionProposal(actor,n.getId());assertThat(r.disabledReasons()).containsExactly("SOURCE_UNAVAILABLE");assertThat(r.validUntil()).isNull();}
    @Test void proposalReadRechecksTimeAfterSlowSources(){doAnswer(i->{when(clocks.getIfAvailable()).thenReturn(Clock.fixed(decision.appointmentAt().plusSeconds(1),ZoneOffset.UTC));return null;}).when(policy).requireFacilityFault(any(),any(),any());assertThatThrownBy(()->service.exceptionProposal(actor,n.getId())).hasMessageContaining("Appointment must not be in the past");}
    @Test void aChangedRentalCannotBeConfirmed(){n.getRental().setStatus(RentalStatus.return_requested);assertThatThrownBy(()->service.exceptionProposal(actor,n.getId())).hasMessageContaining("terms or return state changed");}
    @Test void anotherCustomerCannotReadProposal(){var other=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());assertThatThrownBy(()->service.exceptionProposal(other,n.getId())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(404));}
    @Test void managerCannotUseCustomerEndpoint(){var other=new ActorPrincipal(customer.getId(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(),Map.of());assertThatThrownBy(()->service.exceptionProposal(other,n.getId())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(403));}
    @ParameterizedTest @EnumSource(value=RenewalStatus.class,names={"cancelled","rejected","completed","payment_expired"})
    void terminalRenewalCannotExposeUsableProposal(RenewalStatus status){n.setStatus(status);assertThatThrownBy(()->service.exceptionProposal(actor,n.getId())).hasMessageContaining("Terminal");}
    @Test void currentProposalCanBeConfirmedAndReadBackFromState(){var before=n.getRental().getContractEndDate();var result=service.confirm(actor,n.getId(),new Confirmation(decisionEvent.getId(),3L),"TEST-key");assertThat(result.state().pendingExceptionRef()).isNull();assertThat(result.state().confirmedExceptionRef()).isEqualTo(decisionEvent.getId());assertThat(result.state().appointmentStart()).isEqualTo(decision.appointmentAt());assertThat(service.exceptionProposal(actor,n.getId()).status()).isEqualTo("NONE");assertThat(n.getRental().getContractEndDate()).isEqualTo(before);verify(store).remember(any(),eq("d3_confirm"),eq("TEST-key"),eq(n.getId()),any(),eq(200),any());}
    @Test void supersededProposalCannotConfirm(){state.proposedException(UUID.randomUUID());assertThatThrownBy(()->service.confirm(actor,n.getId(),new Confirmation(decisionEvent.getId(),3L),"TEST-key")).hasMessageContaining("superseded");verify(calendar,never()).reserve(any(),any(),any());}
    @Test void staleWorkflowVersionIsNotBypassed(){when(store.lockWorkflow(n.getId(),2L)).thenThrow(ApiExceptions.conflict("stale version"));assertThatThrownBy(()->service.confirm(actor,n.getId(),new Confirmation(decisionEvent.getId(),2L),"TEST-key")).hasMessageContaining("stale");verify(calendar,never()).reserve(any(),any(),any());}
    @Test void idempotentRetryReturnsOriginalResultWithoutReservingTwice() throws Exception {
        var body=new Confirmation(decisionEvent.getId(),3L);var first=service.confirm(actor,n.getId(),body,"TEST-key");
        assertThat(first.state().expectedVersion()).isEqualTo(4L);
        when(store.replay(any(),anyString(),anyString(),any(),any())).thenReturn(Optional.of(new RenewalPersistence.Replay(200,mapper.writeValueAsString(first))));
        assertThat(service.confirm(actor,n.getId(),body,"TEST-key")).isEqualTo(first);
        verify(calendar,times(1)).reserve(any(),any(),any());verify(safety,times(1)).retain(any(),any(),any());
    }
    @Test void idempotentRetryStillRechecksOwnership() throws Exception {
        var body=new Confirmation(decisionEvent.getId(),3L);var first=service.confirm(actor,n.getId(),body,"TEST-key");
        when(store.replay(any(),anyString(),anyString(),any(),any())).thenReturn(Optional.of(new RenewalPersistence.Replay(200,mapper.writeValueAsString(first))));
        n.getRental().setCustomer(id(new User()));
        assertThatThrownBy(()->service.confirm(actor,n.getId(),body,"TEST-key")).hasMessageContaining("not found");
        verify(calendar,times(1)).reserve(any(),any(),any());
    }
    @Test void revokedFacilityFaultBlocksReadAndConfirm(){doThrow(ApiExceptions.conflict("fault classification revoked")).when(policy).requireFacilityFault(any(),any(),any());assertThatThrownBy(()->service.exceptionProposal(actor,n.getId())).hasMessageContaining("revoked");assertThatThrownBy(()->service.confirm(actor,n.getId(),new Confirmation(decisionEvent.getId(),3L),"TEST-key")).hasMessageContaining("revoked");verify(calendar,never()).reserve(any(),any(),any());}
    @Test void clockAdvancingDuringReservationCannotConfirmLate(){doAnswer(i->{when(clocks.getIfAvailable()).thenReturn(Clock.fixed(decision.appointmentAt().plusSeconds(1),ZoneOffset.UTC));return null;}).when(calendar).reserve(any(),any(),any());assertThatThrownBy(()->service.confirm(actor,n.getId(),new Confirmation(decisionEvent.getId(),3L),"TEST-key")).hasMessageContaining("expired during reservation");assertThat(state.getPendingExceptionRef()).isEqualTo(decisionEvent.getId());verify(store,never()).remember(any(),any(),any(),any(),any(),anyInt(),any());}
    @Test void exactAppointmentBoundaryAlsoExpiresLikeThePublicProposal(){doAnswer(i->{when(clocks.getIfAvailable()).thenReturn(Clock.fixed(decision.appointmentAt(),ZoneOffset.UTC));return null;}).when(calendar).reserve(any(),any(),any());assertThatThrownBy(()->service.confirm(actor,n.getId(),new Confirmation(decisionEvent.getId(),3L),"TEST-boundary")).hasMessageContaining("expired during reservation");assertThat(state.getPendingExceptionRef()).isEqualTo(decisionEvent.getId());verify(store,never()).remember(any(),any(),any(),any(),any(),anyInt(),any());}
}
