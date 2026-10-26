package com.storagehub.service;

import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.UnitType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupEarlyExitPolicyTests {
    private ReturnCase record;
    private ReservationPricingSnapshot pricing;
    private ReservationQuote quote;
    private final Instant setupEnd = Instant.parse("2026-10-29T17:00:00Z");

    @BeforeEach
    void setUp() {
        quote = new ReservationQuote();
        quote.setSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        quote.setSetupEarlyExitFee(new BigDecimal("2000000"));
        var reservation = new Reservation();
        reservation.setSourceQuote(quote);
        var rental = new Rental();
        rental.setReservation(reservation);
        rental.setSetupStartedAt(Instant.parse("2026-10-26T03:00:00Z"));
        rental.setSetupEndsAt(setupEnd);
        record = new ReturnCase();
        record.setRental(rental);
        record.setSetupExitRequested(true);
        record.setSetupReturnedAt(setupEnd.minusSeconds(60));
        pricing = new ReservationPricingSnapshot();
        pricing.setReservation(reservation);
        pricing.setSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        pricing.setMonthlyPrice(new BigDecimal("2000000"));
        pricing.setSetupEarlyExitFee(new BigDecimal("2000000"));
        pricing.setNetRentalAmount(new BigDecimal("6000000"));
    }

    @Test
    void creditsTheWholeVerifiedRentalOnceAndChargesOneSnapshotMonth() {
        SetupEarlyExitPolicy.capture(record, pricing);
        assertThat(record.isSetupEarlyExit()).isTrue();
        assertThat(record.getRentalRefundCredit()).isEqualByComparingTo("6000000");
        assertThat(record.getEarlyTerminationFee()).isEqualByComparingTo("2000000");
        SetupEarlyExitPolicy.capture(record, pricing);
        assertThat(record.getRentalRefundCredit()).isEqualByComparingTo("6000000");
        assertThat(record.getEarlyTerminationFee()).isEqualByComparingTo("2000000");
    }

    @Test
    void liveCatalogPriceDoesNotChangeAcceptedEarlyExitFee() {
        var currentType = new UnitType();
        currentType.setMonthlyPrice(new BigDecimal("9000000"));
        record.getRental().getReservation().setUnitType(currentType);
        record.getRental().setMonthlyPrice(new BigDecimal("9000000"));
        SetupEarlyExitPolicy.capture(record, pricing);
        assertThat(record.getEarlyTerminationFee()).isEqualByComparingTo("2000000");
    }

    @Test
    void setupEndIsExclusiveEvenWhenRequestWasCreatedDuringSetup() {
        record.setRequestedAt(setupEnd.minusSeconds(3600));
        record.setSetupReturnedAt(setupEnd);
        SetupEarlyExitPolicy.capture(record, pricing);
        assertThat(record.isSetupEarlyExit()).isFalse();
        assertThat(record.getRentalRefundCredit()).isZero();
        assertThat(record.getEarlyTerminationFee()).isZero();
        record.setSetupReturnedAt(setupEnd.plusSeconds(1));
        assertThat(SetupEarlyExitPolicy.applies(record)).isFalse();
    }

    @Test
    void normalOrRecoveryReturnDoesNotReceiveSetupCredit() {
        record.setSetupExitRequested(false);
        SetupEarlyExitPolicy.capture(record, pricing);
        assertThat(record.isSetupEarlyExit()).isFalse();
        assertThat(record.getRentalRefundCredit()).isZero();
        assertThat(record.getEarlyTerminationFee()).isZero();
    }

    @Test
    void historicalTermsAreNotReplacedByTheCurrentPolicy() {
        pricing.setSetupEarlyExitPolicyVersion(null);
        pricing.setSetupEarlyExitFee(null);
        assertThatThrownBy(() -> SetupEarlyExitPolicy.capture(record, pricing))
            .hasMessageContaining("đối soát thủ công");
    }

    @Test
    void changedFeeOrUnacceptedQuoteCannotBeUsedForRefund() {
        pricing.setSetupEarlyExitFee(new BigDecimal("1000000"));
        assertThatThrownBy(() -> SetupEarlyExitPolicy.capture(record, pricing)).hasMessageContaining("đã chấp nhận");
        pricing.setSetupEarlyExitFee(new BigDecimal("2000000"));
        quote.setSetupEarlyExitPolicyVersion(null);
        assertThatThrownBy(() -> SetupEarlyExitPolicy.capture(record, pricing)).hasMessageContaining("đã chấp nhận");
    }

    @Test
    void missingHandoverOrPhysicalReturnEvidenceRequiresReconciliation() {
        record.setSetupReturnedAt(null);
        assertThatThrownBy(() -> SetupEarlyExitPolicy.capture(record, pricing)).hasMessageContaining("Thiếu chứng cứ");
        record.setSetupReturnedAt(setupEnd.minusSeconds(60));
        record.getRental().setSetupStartedAt(null);
        assertThatThrownBy(() -> SetupEarlyExitPolicy.capture(record, pricing)).hasMessageContaining("Thiếu chứng cứ");
    }
}
