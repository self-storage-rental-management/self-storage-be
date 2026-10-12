package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.PaymentProvenance.Classification;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentReconciliationPreflightTests {
    final LedgerStore ledger=mock(LedgerStore.class);
    final ObjectProvider<LedgerStore> provider=mock(ObjectProvider.class);
    final PaymentReconciliationPreflight preflight=new PaymentReconciliationPreflight(provider,new SandboxPaymentRecognitionPolicy("VNPAY_SANDBOX",true));
    <T extends BaseEntity>T id(T e){ReflectionTestUtils.setField(e,"id",UUID.randomUUID());return e;}
    record Fixture(Payment payment,Rental rental,ReservationPricingSnapshot snapshot){}
    Fixture fixture(){when(provider.getIfAvailable()).thenReturn(ledger);var res=id(new Reservation());res.setCustomer(id(new User()));res.setFacility(id(new Facility()));var r=id(new Rental());r.setReservation(res);r.setCustomer(res.getCustomer());r.setFacility(res.getFacility());var p=id(new Payment());p.setReservation(res);p.setAmount(new BigDecimal("108"));p.setPurpose(PaymentType.RESERVATION_DEPOSIT);var s=id(FinancialContractSpecificationsTests.snapshot());s.setReservation(res);return new Fixture(p,r,s);}
    void state(Fixture f,List<LedgerStore.Obligation> obligations){when(ledger.read(f.rental().getId())).thenReturn(Optional.of(new LedgerStore.Snapshot(f.rental().getId(),f.rental().getCustomer().getId(),f.rental().getFacility().getId(),0,obligations,List.of(),List.of())));}
    @Test void paymentBeforeRentalActivationWaitsWithoutMutation(){var f=fixture();assertThat(preflight.read(f.payment(),null,f.snapshot(),Classification.SANDBOX).status()).isEqualTo("WAITING_RENTAL_ACTIVATION");verifyNoInteractions(ledger);}
    @Test void simulatedMoneyNeverProducesAllocation(){var f=fixture();assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SIMULATED).status()).isEqualTo("NON_VERIFIED_MONEY");verifyNoInteractions(ledger);}
    @Test void productionPaymentIsOutOfProjectScopeEvenWhenVerified(){var f=fixture();assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.VERIFIED).status()).isEqualTo("NON_VERIFIED_MONEY");verifyNoInteractions(ledger);}
    @Test void disabledSandboxPolicyDoesNotReadOrPostLedger(){var f=fixture();var disabled=new PaymentReconciliationPreflight(provider,new SandboxPaymentRecognitionPolicy("VNPAY_SANDBOX",false));assertThat(disabled.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX).status()).isEqualTo("SANDBOX_POLICY_DISABLED");verifyNoInteractions(ledger);}
    @Test void crossRentalAndDoubleReferenceAreRejected(){var f=fixture();var other=id(new Reservation());other.setCustomer(f.rental().getCustomer());other.setFacility(f.rental().getFacility());f.payment().setReservation(other);assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX).status()).isEqualTo("CROSS_RENTAL_REJECTED");f.payment().setReservation(f.rental().getReservation());state(f,List.of());when(ledger.receiptEvidenceRental("payment:"+f.payment().getId())).thenReturn(Optional.of(UUID.randomUUID()));assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX).status()).isEqualTo("CROSS_RENTAL_REJECTED");when(ledger.receiptEvidenceRental("payment:"+f.payment().getId())).thenReturn(Optional.of(f.rental().getId()));assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX).status()).isEqualTo("PAYMENT_ALREADY_REFERENCED");}
    @Test void incompleteIdentityCannotReachLedger(){
        var f=fixture();f.rental().setReservation(null);
        assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX).status()).isEqualTo("SOURCE_IDENTITY_UNKNOWN");
        verify(ledger,never()).read(any());verify(ledger,never()).receiptEvidenceRental(any());
    }
    @Test void facilityDriftIsRejectedBeforeMoneyClassification(){
        var f=fixture();f.rental().setFacility(id(new Facility()));
        assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SIMULATED).status()).isEqualTo("CROSS_RENTAL_REJECTED");
        verify(ledger,never()).read(any());verify(ledger,never()).receiptEvidenceRental(any());
    }
    @Test void foreignPricingSnapshotCannotBecomeAllocationCandidate(){
        var f=fixture();f.snapshot().setReservation(id(new Reservation()));
        assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX).status()).isEqualTo("CONTRACT_SOURCE_MISMATCH");
        verify(ledger,never()).read(any());verify(ledger,never()).receiptEvidenceRental(any());
    }
    @Test void uniqueRentMappingIsDeterministicButDoesNotBypassEvidenceContract(){var f=fixture();var spec=FinancialContractSpecifications.initial(f.rental(),f.snapshot()).getFirst();var target=new LedgerStore.Obligation(UUID.randomUUID(),null,LedgerStore.Kind.RENT,new BigDecimal("270"),Instant.now(),spec.sourceReference(),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("270"));state(f,List.of(target));var first=preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX);assertThat(first.status()).isEqualTo("SANDBOX_RECEIPT_READY");assertThat(first.obligationId()).isEqualTo(target.id());assertThat(preflight.read(f.payment(),f.rental(),f.snapshot(),Classification.SANDBOX)).isEqualTo(first);verify(ledger,never()).receive(any(),any(),any(),any(),any(),any(),any(),any(),anyLong(),any());}
}
