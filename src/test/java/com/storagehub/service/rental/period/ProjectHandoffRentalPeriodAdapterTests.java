package com.storagehub.service.rental.period;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Test-only records/query results. No DB, seed, network, runtime fallback or mutation. */
class ProjectHandoffRentalPeriodAdapterTests {
    EntityManager em;
    ProjectHandoffRentalPeriodAdapter adapter;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Rental rental;
    CheckIn handover;
    ActivityLog receipt;
    TypedQuery<CheckIn> checkIns;
    TypedQuery<RenewalOperationEvent> events;
    TypedQuery<UUID> completed;
    TypedQuery<ActivityLog> logs;

    @BeforeEach void setup() throws Exception {
        em=mock(EntityManager.class);adapter=new ProjectHandoffRentalPeriodAdapter(em,mapper);
        var f=id(new Facility());var u=id(new User());var unit=id(new StorageUnit());unit.setFacility(f);
        var res=id(new Reservation());res.setCustomer(u);res.setFacility(f);res.setAssignedUnit(unit);
        res.setStatus(ReservationStatus.COMPLETED);res.setStartDate(LocalDate.of(2026,10,1));res.setEndDate(LocalDate.of(2026,11,1));
        rental=id(new Rental());rental.setCustomer(u);rental.setFacility(f);rental.setStorageUnit(unit);rental.setReservation(res);
        rental.setStartDate(res.getStartDate());rental.setContractEndDate(res.getEndDate());
        handover=id(new CheckIn());handover.setReservation(res);handover.setRental(rental);handover.setStatus(CheckInStatus.completed);
        receipt=id(new ActivityLog());receipt.setActor(u);receipt.setFacility(f);receipt.setAction("CUSTOMER_RECEIPT_CONFIRMED");
        receipt.setEntityType("Reservation");receipt.setEntityId(res.getId());proof(true);
        checkIns=query(CheckIn.class);events=query(RenewalOperationEvent.class);completed=query(UUID.class);logs=query(ActivityLog.class);
        when(checkIns.getResultList()).thenReturn(List.of(handover));when(events.getResultList()).thenReturn(List.of());
        when(completed.getResultList()).thenReturn(List.of());when(logs.getResultList()).thenReturn(List.of(receipt));
    }
    @SuppressWarnings("unchecked") <T> TypedQuery<T> query(Class<T> type) {
        var q=(TypedQuery<T>)mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(type))).thenReturn(q);
        when(q.setParameter(anyString(),any())).thenReturn(q);when(q.setMaxResults(anyInt())).thenReturn(q);return q;
    }
    <T extends BaseEntity> T id(T entity) {ReflectionTestUtils.setField(entity,"id",UUID.randomUUID());return entity;}
    void proof(boolean created) throws Exception {
        receipt.setAfterStateJson(mapper.writeValueAsString(Map.of("status","COMPLETED","rentalId",rental.getId(),
            "rentalPeriodEvidence",RentalPeriodEvidence.receipt(rental,created))));
    }
    @Test void genuineNewReceiptReadsExclusiveDatesAndNeverWrites() {
        var d=adapter.read(rental).orElseThrow();
        assertThat(d.storedEndDate()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(d.inclusiveEndDate()).isEqualTo(LocalDate.of(2026,10,31));
        assertThat(d.convention()).isEqualTo(RentalPeriod.Convention.EXCLUSIVE);
        assertThat(RentalPeriodResolver.normalize(rental,d)).isPresent();
        assertThat(rental.getContractEndDate()).isEqualTo(LocalDate.of(2026,11,1));
        verify(em,never()).persist(any());verify(em,never()).merge(any());verify(em,never()).flush();
        verify(checkIns).setParameter("id",rental.getReservation().getId());
        verify(logs).setParameter("id",rental.getReservation().getId());verify(logs).setMaxResults(2);
    }
    @Test void beforeCustomerReceiptNoRentalIsCreatedOrQueried() {
        rental.getReservation().setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        clearInvocations(em);
        assertThat(adapter.read(rental)).isEmpty();verifyNoInteractions(em);
    }
    @Test void reusedRentalKeepsUnknownEvenWhenAllDatesMatch() throws Exception {
        proof(false);assertThat(adapter.read(rental)).isEmpty();
    }
    @Test void oldAuditWithNoCreatedFlagDoesNotBecomeProof() throws Exception {
        receipt.setAfterStateJson(mapper.writeValueAsString(Map.of("status","COMPLETED","rentalId",rental.getId())));
        assertThat(adapter.read(rental)).isEmpty();
    }
    @Test void malformedOrNullAuditFailsClosed() {
        for(var json:List.of("broken","null","{}","[]")) {
            receipt.setAfterStateJson(json);assertThat(adapter.read(rental)).isEmpty();
        }
    }
    @Test void duplicateReceiptIsAmbiguous() {
        when(logs.getResultList()).thenReturn(List.of(receipt,receipt));assertThat(adapter.read(rental)).isEmpty();
    }
    @Test void wrongActorFacilityOrAuditResourceIsNotProof() {
        receipt.setActor(id(new User()));assertThat(adapter.read(rental)).isEmpty();receipt.setActor(rental.getCustomer());
        receipt.setFacility(id(new Facility()));assertThat(adapter.read(rental)).isEmpty();receipt.setFacility(rental.getFacility());
        receipt.setEntityId(UUID.randomUUID());assertThat(adapter.read(rental)).isEmpty();
    }
    @Test void differentCheckInRentalOrReservationAndIncompleteHandoverAreUnknown() {
        handover.setRental(id(new Rental()));assertThat(adapter.read(rental)).isEmpty();handover.setRental(rental);
        handover.setReservation(id(new Reservation()));assertThat(adapter.read(rental)).isEmpty();handover.setReservation(rental.getReservation());
        handover.setStatus(CheckInStatus.scheduled);assertThat(adapter.read(rental)).isEmpty();
    }
    @Test void changedRawDatesCannotReuseOldTupleEvidence() {
        rental.setContractEndDate(LocalDate.of(2026,12,1));assertThat(adapter.read(rental)).isEmpty();
        rental.getReservation().setEndDate(rental.getContractEndDate());assertThat(adapter.read(rental)).isEmpty();
    }
    @Test void foreignUnitFailsBeforeQuerying() {
        rental.getReservation().setAssignedUnit(id(new StorageUnit()));clearInvocations(em);
        assertThat(adapter.read(rental)).isEmpty();verifyNoInteractions(em);
    }
    @Test void completedLegacyRenewalWithoutCompletionEvidenceDoesNotFallBackToReceipt() {
        when(completed.getResultList()).thenReturn(List.of(UUID.randomUUID()));assertThat(adapter.read(rental)).isEmpty();
        verify(logs,never()).getResultList();
    }
    @Test void malformedLatestCompletionDoesNotFallBackToReceipt() {
        var n=id(new Renewal());n.setRental(rental);n.setStatus(RenewalStatus.completed);
        var e=id(new RenewalOperationEvent(n,"COMPLETION",java.time.Instant.now(),UUID.randomUUID(),"{}"));
        when(events.getResultList()).thenReturn(List.of(e));assertThat(adapter.read(rental)).isEmpty();
        verify(logs,never()).getResultList();
    }
    @Test void infrastructureFailureIsNotAnEmptySuccess() {
        when(logs.getResultList()).thenThrow(new IllegalStateException("test source outage"));
        assertThatThrownBy(()->adapter.read(rental)).hasMessage("test source outage");
    }
    @Test void receiptMetadataIsAdditiveAndToleratesReusedIncompleteLegacyRelations() {
        var partial=id(new Rental());assertThat(RentalPeriodEvidence.receipt(partial,false)).containsEntry("convention","UNKNOWN");
        assertThat(RentalPeriodEvidence.receipt(rental,true)).containsEntry("rentalCreated",true)
            .containsEntry("storedEndDate",rental.getContractEndDate().toString());
    }
    @Test void productionAdapterIsExplicitlyOptInUntilConsumersAreApproved() {
        var flag=ProjectHandoffRentalPeriodAdapter.class.getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(flag.matchIfMissing()).isFalse();assertThat(flag.havingValue()).isEqualTo("true");
    }
}
