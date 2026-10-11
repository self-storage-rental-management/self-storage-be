package com.storagehub.service.rental;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** SQL-backed rollback tests; these fixtures exist only inside the H2 test transaction. */
@DataJpaTest @ActiveProfiles("test") @Import(RentalBookingEvidenceReader.class)
class RentalBookingEvidenceJpaTests {
    @Autowired EntityManager em;
    @Autowired RentalBookingEvidenceReader reader;
    private static final Instant NOW=Instant.parse("2026-10-10T00:00:00Z");
    private ActorPrincipal customer(Rental r) {
        return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
    }
    private ActorPrincipal manager(Rental r,boolean payments,boolean scope) {
        return new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),
            payments?Set.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.VIEW_PAYMENTS.code()):Set.of(SystemPermission.VIEW_RENTALS.code()),
            scope?Map.of(r.getFacility().getId(),FacilityScopeLevel.READ):Map.of());
    }
    @Test void readsAgreedAmountsAndSimulationWithoutClaimingRealReceiptOrUpdatingRental() {
        var r=fixture();snapshot(r);payment(r,"SIMULATED-",0);em.flush();em.clear();
        r=em.find(Rental.class,r.getId());var updated=r.getUpdatedAt();
        var evidence=reader.read(customer(r),r,false).orElseThrow();
        assertThat(evidence.rentalId()).isEqualTo(r.getId());assertThat(evidence.reservationId()).isEqualTo(r.getReservation().getId());
        assertThat(evidence.agreedAmounts().securityDepositAmount()).isEqualByComparingTo("10");
        assertThat(evidence.payments()).hasSize(1);assertThat(evidence.payments().getFirst().verification()).isEqualTo("SIMULATED");
        assertThat(evidence.payments().getFirst().status()).isEqualTo("PAID");
        em.flush();em.clear();assertThat(em.find(Rental.class,r.getId()).getUpdatedAt()).isEqualTo(updated);
    }
    @Test void noSnapshotOrPaymentsMeansAbsentEvidenceNotZeroMoney() {
        var r=fixture();var evidence=reader.read(customer(r),r,false).orElseThrow();
        assertThat(evidence.agreedAmounts()).isNull();assertThat(evidence.payments()).isEmpty();assertThat(evidence.paymentsTruncated()).isFalse();
    }
    @Test void paidWithNonSimulationReferenceStillDoesNotProveRealReceipt() {
        var r=fixture();payment(r,"UNVERIFIED-",0);em.flush();
        assertThat(reader.read(customer(r),r,false).orElseThrow().payments().getFirst().verification()).isEqualTo("UNVERIFIED");
    }
    @Test void legacyGatewayMarkerDoesNotProveRealOrSimulatedPaymentAndIsNotExposed() {
        var r=fixture();payment(r,"LEGACY-UNVERIFIED-",0);em.flush();
        var evidence=reader.read(customer(r),r,false).orElseThrow();
        assertThat(evidence.payments().getFirst().verification()).isEqualTo("UNVERIFIED");
        assertThat(evidence.payments().getFirst().status()).isEqualTo("PAID");
        assertThat(evidence.payments().getFirst().toString()).doesNotContain("LEGACY-UNVERIFIED-");
    }
    @Test void rentalReadPermissionAloneDoesNotExposePaymentsAndForeignScopeIsDenied() {
        var r=fixture();snapshot(r);payment(r,"SIMULATED-",0);em.flush();
        assertThat(reader.read(manager(r,false,true),r,true)).isEmpty();
        assertThat(reader.read(manager(r,true,true),r,true).orElseThrow().payments()).hasSize(1);
        var rental=r;assertThatThrownBy(()->reader.read(manager(rental,true,false),rental,true)).hasMessageContaining("not found");
    }
    @Test void foreignCustomerAndWrongRoleAreDenied() {
        var r=fixture();var foreign=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
        assertThatThrownBy(()->reader.read(foreign,r,false)).hasMessageContaining("not found");
        var staff=new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.STAFF),Set.of(),Map.of());
        assertThatThrownBy(()->reader.read(staff,r,false)).hasMessageContaining("not found");
        assertThatThrownBy(()->reader.read(null,r,false)).hasMessageContaining("Authentication required");
    }
    @Test void inconsistentSnapshotFailsInsteadOfInventingFinancialCompleteness() {
        var r=fixture();var snapshot=snapshot(r);snapshot.setDueAtCheckIn(BigDecimal.ZERO);em.flush();
        assertThatThrownBy(()->reader.read(customer(r),r,false)).hasMessageContaining("totals are inconsistent");
    }
    @Test void historyIsBoundedAndTruncationExplicit() {
        var r=fixture();for(int i=0;i<21;i++)payment(r,"SIMULATED-",i);em.flush();
        var evidence=reader.read(customer(r),r,false).orElseThrow();assertThat(evidence.payments()).hasSize(20);
        assertThat(evidence.paymentsTruncated()).isTrue();
    }
    private Payment payment(Rental r,String prefix,int i) {
        var p=new Payment();p.setInitiatedBy(r.getCustomer());p.setReservation(r.getReservation());p.setAmount(new BigDecimal("20"));
        p.setCurrency("VND");p.setPurpose(PaymentType.RESERVATION_DEPOSIT);p.setStatus(PaymentStatus.PAID);
        p.setIdempotencyKey("TEST-"+i);p.setGatewayIntentId(prefix+i);p.setProcessedAt(NOW);em.persist(p);return p;
    }
    private ReservationPricingSnapshot snapshot(Rental r) {
        var s=new ReservationPricingSnapshot();s.setReservation(r.getReservation());s.setPricingPackageCode("TEST");s.setPricingPolicyVersion("TEST");
        s.setRentalMonths(1);s.setMonthlyPrice(new BigDecimal("100"));s.setGrossRentalAmount(new BigDecimal("100"));s.setNetRentalAmount(new BigDecimal("100"));
        s.setReservationDepositAmount(new BigDecimal("20"));s.setSecurityDepositAmount(new BigDecimal("10"));s.setRemainingRentalAmount(new BigDecimal("80"));
        s.setDueAtCheckIn(new BigDecimal("90"));s.setTotalInitialObligation(new BigDecimal("110"));s.setQuotedAt(NOW);s.setExpiresAt(NOW.plusSeconds(1800));em.persist(s);return s;
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
