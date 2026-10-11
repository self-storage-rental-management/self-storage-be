package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.service.overdue.OverdueFollowUp;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** H2-only rollback fixtures. No real policy, payment, booking or maintenance mutation is performed. */
@DataJpaTest(properties="storagehub.integration.renewal-operational-checks.enabled=true")
@ActiveProfiles("test") @Import(RenewalOperationalGuard.class)
class RenewalOperationalGuardJpaTests {
    private static final Instant NOW=Instant.parse("2026-10-10T00:00:00Z");
    @Autowired EntityManager em;
    @Autowired RenewalOperationalGuard guard;

    @Test void passingNegativeChecksDoesNotMutateDataOrProvideEligibilityHoldOrMoney() {
        var r=fixture();var id=r.getId();em.clear();var current=em.find(Rental.class,id);
        var updated=current.getUpdatedAt();var unitUpdated=current.getStorageUnit().getUpdatedAt();
        guard.requireNoKnownConflict(current);em.flush();em.clear();
        current=em.find(Rental.class,id);
        assertThat(current.getContractEndDate()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(current.getStatus()).isEqualTo(RentalStatus.active);
        assertThat(current.getUpdatedAt()).isEqualTo(updated);
        assertThat(current.getStorageUnit().getUpdatedAt()).isEqualTo(unitUpdated);
        assertThat(current.getStorageUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        assertThat(em.createQuery("select count(x) from Renewal x",Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(x) from RenewalOperationEvent x",Long.class).getSingleResult()).isZero();
    }
    @Test void absentContextIsUnknownNotAvailable() {
        assertThatThrownBy(()->guard.requireNoKnownConflict(null)).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
    }
    @ParameterizedTest @EnumSource(value=StorageUnitStatus.class,names={"available","reserved","maintenance"})
    void nonOccupiedPhysicalUnitBlocksWithoutRepair(StorageUnitStatus status) {
        var r=fixture();r.getStorageUnit().setStatus(status);em.flush();
        assertThatThrownBy(()->guard.requireNoKnownConflict(r)).hasMessageContaining("no longer occupied");
        assertThat(r.getStorageUnit().getStatus()).isEqualTo(status);
    }
    @Test void returnRequestedBlocksEvenWhenNoReturnRowExists() {
        var r=fixture();r.setStatus(RentalStatus.return_requested);em.flush();
        assertThatThrownBy(()->guard.requireNoKnownConflict(r)).hasMessageContaining("non-active rental");
    }
    @Test void persistedReturnCaseBlocksEvenIfRentalWasNotUpdated() {
        var r=fixture();var rc=new ReturnCase();rc.setRental(r);rc.setFacility(r.getFacility());
        rc.setStorageUnit(r.getStorageUnit());rc.setCustomer(r.getCustomer());rc.setRequestedBy(r.getCustomer());
        rc.setRequestedAt(NOW);rc.setScheduledDate(LocalDate.of(2026,10,20));em.persist(rc);em.flush();
        assertThatThrownBy(()->guard.requireNoKnownConflict(r)).hasMessageContaining("Existing return case");
    }
    @ParameterizedTest @EnumSource(value=MaintenanceTaskStatus.class,names={"open","in_progress"})
    void openMaintenanceBlocksOccupiedUnit(MaintenanceTaskStatus status) {
        var r=fixture();task(r,status);em.flush();
        assertThatThrownBy(()->guard.requireNoKnownConflict(r)).hasMessageContaining("Active maintenance");
    }
    @ParameterizedTest @EnumSource(value=MaintenanceTaskStatus.class,names={"completed","cancelled"})
    void terminalMaintenanceIsNotAnActiveConflict(MaintenanceTaskStatus status) {
        var r=fixture();task(r,status);em.flush();assertThatCode(()->guard.requireNoKnownConflict(r)).doesNotThrowAnyException();
    }
    @Test void foreignActiveRentalBlocksSameUnit() {
        var r=fixture();var other=new Rental();other.setCustomer(r.getCustomer());other.setFacility(r.getFacility());
        other.setStorageUnit(r.getStorageUnit());other.setReservation(r.getReservation());other.setStartDate(r.getStartDate());
        other.setContractEndDate(r.getContractEndDate());other.setMonthlyPrice(BigDecimal.ONE);em.persist(other);em.flush();
        assertThatThrownBy(()->guard.requireNoKnownConflict(r)).hasMessageContaining("Another rental");
    }
    @Test void otherAssignedBookingBlocksWithoutCancellingIt() {
        var r=fixture();var other=reservation(r,"OTHER",ReservationStatus.CONFIRMED);other.setAssignedUnit(r.getStorageUnit());em.persist(other);em.flush();
        assertThatThrownBy(()->guard.requireNoKnownConflict(r)).hasMessageContaining("Another booking");
        assertThat(other.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }
    @Test void ownAssignedBookingAndOtherCancelledBookingDoNotBlock() {
        var r=fixture();r.getReservation().setAssignedUnit(r.getStorageUnit());
        var other=reservation(r,"OTHER",ReservationStatus.CANCELLED);other.setAssignedUnit(r.getStorageUnit());em.persist(other);em.flush();
        assertThatCode(()->guard.requireNoKnownConflict(r)).doesNotThrowAnyException();
    }
    @Test void recordedRecoveryHandoffRequiresReceiverReconciliation() {
        var r=fixture();em.persist(new OverdueFollowUp("RENTAL_TERM:"+r.getId(),r,"RECOVERY_HANDOFF","TEST",r.getCustomer().getId(),NOW,UUID.randomUUID(),"TEST","1"));em.flush();
        assertThatThrownBy(()->guard.requireNoKnownConflict(r)).hasMessageContaining("Recovery handoff");
    }
    private void task(Rental r,MaintenanceTaskStatus status) {
        var task=new MaintenanceTask();task.setFacility(r.getFacility());task.setStorageUnit(r.getStorageUnit());
        task.setTitle("TEST");task.setReason("TEST");task.setReportedBy(r.getCustomer());task.setStatus(status);em.persist(task);
    }
    private Reservation reservation(Rental r,String code,ReservationStatus status) {
        var res=new Reservation();res.setReservationCode(code);res.setIdempotencyKey(code);res.setCustomer(r.getCustomer());
        res.setSourceQuote(r.getReservation().getSourceQuote());res.setFacility(r.getFacility());res.setUnitType(r.getStorageUnit().getUnitType());
        res.setStartDate(r.getStartDate());res.setEndDate(r.getContractEndDate());res.setStatus(status);return res;
    }
    private Rental fixture() {
        var f=new Facility();f.setCode("TEST");f.setName("TEST");f.setAddress("TEST");f.setCity("TEST");em.persist(f);
        var u=new User();u.setEmail("fixture@test.invalid");u.setFullName("TEST");u.setPasswordHash("TEST-ONLY");em.persist(u);
        var t=new UnitType();t.setFacility(f);t.setCode("S");t.setName("TEST");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);
        t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(BigDecimal.ONE);t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);
        t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        var unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode("TEST-1");unit.setStatus(StorageUnitStatus.occupied);em.persist(unit);
        var q=new ReservationQuote();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("TEST");
        q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,11,1));q.setRentalMonths(1);q.setMonthlyPrice(BigDecimal.ONE);
        q.setSubtotal(BigDecimal.ONE);q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(BigDecimal.ONE);
        q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(BigDecimal.ONE);
        q.setDueAtCheckIn(BigDecimal.ONE);q.setTotalInitialObligation(BigDecimal.ONE);q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        var res=new Reservation();res.setReservationCode("TEST");res.setIdempotencyKey("TEST");res.setCustomer(u);res.setSourceQuote(q);
        res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());res.setStatus(ReservationStatus.COMPLETED);em.persist(res);
        var r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());
        r.setContractEndDate(q.getEndDate());r.setMonthlyPrice(BigDecimal.ONE);em.persist(r);em.flush();return r;
    }
}
