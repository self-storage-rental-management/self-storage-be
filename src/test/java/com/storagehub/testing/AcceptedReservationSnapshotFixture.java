package com.storagehub.testing;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/** Test-only accepted quote matching a declared snapshot; never changes runtime validation. */
public final class AcceptedReservationSnapshotFixture {
    private AcceptedReservationSnapshotFixture() {}

    public static void bind(Reservation reservation, ReservationPricingSnapshot snapshot) {
        var quote = reservation.getSourceQuote();
        if (quote == null) {
            quote = LegacyReservationQuoteFixture.create();
            ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
            reservation.setSourceQuote(quote);
        }
        quote.setCustomer(reservation.getCustomer());
        quote.setFacility(reservation.getFacility());
        quote.setUnitType(reservation.getUnitType());
        quote.setStartDate(reservation.getStartDate());
        quote.setEndDate(reservation.getEndDate());
        if (snapshot.getPricingPolicyVersion() == null) snapshot.setPricingPolicyVersion("accepted-test-v1");
        if (snapshot.getQuotedAt() == null) snapshot.setQuotedAt(Instant.now().minusSeconds(3600));
        snapshot.setReservation(reservation);
        quote.setPricingPackageCode(snapshot.getPricingPackageCode());
        quote.setPolicyVersion(snapshot.getPricingPolicyVersion());
        quote.setRentalMonths(snapshot.getRentalMonths());
        quote.setFreeSetupDays(snapshot.getFreeSetupDays());
        reservation.setSetupDaysSnapshot(snapshot.getFreeSetupDays());
        quote.setQuotedAt(snapshot.getQuotedAt());
        quote.setExpiresAt(snapshot.getExpiresAt());
        quote.setMonthlyPrice(snapshot.getMonthlyPrice());
        quote.setSubtotal(snapshot.getGrossRentalAmount());
        quote.setDiscountRate(snapshot.getDiscountRate());
        quote.setDiscountAmount(snapshot.getDiscountAmount());
        quote.setTotalAfterDiscount(snapshot.getNetRentalAmount());
        quote.setReservationDepositAmount(snapshot.getReservationDepositAmount());
        quote.setSecurityDepositAmount(snapshot.getSecurityDepositAmount());
        quote.setRemainingRentalAmount(snapshot.getRemainingRentalAmount());
        quote.setDueAtCheckIn(snapshot.getDueAtCheckIn());
        quote.setTotalInitialObligation(snapshot.getTotalInitialObligation());
        quote.setSetupEarlyExitPolicyVersion(snapshot.getSetupEarlyExitPolicyVersion());
        quote.setSetupEarlyExitFee(snapshot.getSetupEarlyExitFee());
        quote.setUnitTypeSnapshotJson("{\"name\":\"Accepted test unit\"}");
    }
}
