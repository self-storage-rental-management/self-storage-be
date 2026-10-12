package com.storagehub.service.ledger;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.renewal.persistence.RenewalAcceptedRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class LedgerCoverageInventoryTests {
    @Test void emptyBoundAccountRemainsPartialWithExplicitMissingSources() {
        var rental=rental();var inventory=inventory(rental,List.of());
        var result=inventory.inspect(rental,Optional.of(snapshot(rental)));
        assertThat(result.completeness()).isEqualTo("PARTIAL");
        assertThat(result.gaps()).contains("BOOKING_SNAPSHOT_MISSING","BOOKING_PAYMENT_EVIDENCE_MISSING","HISTORICAL_WRITER_COVERAGE_UNCERTIFIED");
        assertThat(result.consistentThroughApproval()).isFalse();
        assertThat(result.ledgerRevision()).isZero();
    }
    @Test void absentAccountIsUnknownAndCrossResourceBindingIsDiagnosed() {
        var rental=rental();var inventory=inventory(rental,List.of());
        assertThat(inventory.inspect(rental,Optional.empty()).completeness()).isEqualTo("UNKNOWN");
        var wrong=new LedgerStore.Snapshot(UUID.randomUUID(),rental.getCustomer().getId(),rental.getFacility().getId(),0,List.of(),List.of(),List.of());
        assertThat(inventory.inspect(rental,Optional.of(wrong)).gaps()).contains("LEDGER_BINDING_INVALID");
    }
    @Test void simulatedDisputedAndPendingRefundEvidenceNeverCertifyCoverage() {
        var rental=rental();var payment=new Payment();ReflectionTestUtils.setField(payment,"id",UUID.randomUUID());
        payment.setReservation(rental.getReservation());payment.setStatus(PaymentStatus.PAID);payment.setRefundedAmount(java.math.BigDecimal.TEN);
        var inventory=inventory(rental,List.of(payment));var source=(PaymentProvenance)ReflectionTestUtils.getField(inventory,"provenance");
        when(source.classify(payment)).thenReturn(PaymentProvenance.Classification.SIMULATED);
        var result=inventory.inspect(rental,Optional.of(snapshot(rental)));
        assertThat(result.gaps()).contains("PAYMENT_NOT_PROJECT_SANDBOX","PAYMENT_REFUND_EXECUTION_UNRECONCILED");
        assertThat(result.payments().getFirst().provenance()).isEqualTo("SIMULATED");
        when(source.classify(payment)).thenReturn(PaymentProvenance.Classification.DISPUTED);
        assertThat(inventory.inspect(rental,Optional.of(snapshot(rental))).gaps()).contains("PAYMENT_DISPUTED");
    }
    private LedgerCoverageInventory inventory(Rental rental,List<Payment> history) {
        var pricing=mock(ReservationPricingSnapshotRepository.class);var payments=mock(PaymentRepository.class);
        when(pricing.findByReservation_Id(rental.getReservation().getId())).thenReturn(Optional.empty());
        when(payments.findAllByReservation_IdAndStatusIn(eq(rental.getReservation().getId()),any())).thenReturn(history);
        var em=mock(EntityManager.class);var query=mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(RenewalAcceptedRevision.class))).thenReturn(query);
        when(query.setParameter(eq("id"),any())).thenReturn(query);when(query.getResultList()).thenReturn(List.of());
        return new LedgerCoverageInventory(pricing,payments,mock(PaymentProvenance.class),em,new ObjectMapper(),new SandboxPaymentRecognitionPolicy("VNPAY_SANDBOX",true),mock(LedgerStore.class),mock(ActivityLogRepository.class));
    }
    private Rental rental() {
        var r=new Rental();var customer=new User();var facility=new Facility();var reservation=new Reservation();
        for(var entity:List.of(r,customer,facility,reservation))ReflectionTestUtils.setField(entity,"id",UUID.randomUUID());
        r.setCustomer(customer);r.setFacility(facility);r.setReservation(reservation);reservation.setCustomer(customer);reservation.setFacility(facility);return r;
    }
    private LedgerStore.Snapshot snapshot(Rental r){return new LedgerStore.Snapshot(r.getId(),r.getCustomer().getId(),r.getFacility().getId(),0,List.of(),List.of(),List.of());}
}
