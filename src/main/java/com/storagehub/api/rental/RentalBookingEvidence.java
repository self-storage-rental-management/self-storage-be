package com.storagehub.api.rental;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Original agreed amounts and payment records, not proof of current financial completeness or real receipt. */
public record RentalBookingEvidence(UUID rentalId,UUID reservationId,String currency,Amounts agreedAmounts,
    List<PaymentRecord> payments,boolean paymentsTruncated) {
    public record Amounts(BigDecimal netRentalAmount,BigDecimal reservationDepositAmount,BigDecimal securityDepositAmount,
        BigDecimal remainingRentalAmount,BigDecimal dueAtCheckIn,BigDecimal totalInitialObligation) {}
    public record PaymentRecord(UUID id,String purpose,String status,BigDecimal amount,Instant processedAt,String verification,
        com.storagehub.service.ledger.PaymentReconciliationPreflight.Plan reconciliation) {
        public PaymentRecord(UUID id,String purpose,String status,BigDecimal amount,Instant processedAt,String verification) {
            this(id,purpose,status,amount,processedAt,verification,null);
        }
    }
}
