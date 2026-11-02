package com.storagehub.api.rental;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.storagehub.api.file.FileAssetResponse;

/** Original agreed amounts and payment records, not proof of current financial completeness or real receipt. */
public record RentalBookingEvidence(UUID rentalId,UUID reservationId,String currency,Amounts agreedAmounts,
    List<PaymentRecord> payments,boolean paymentsTruncated,CustomerSnapshot customer,
    List<GoodsItem> goodsItems) {
    public RentalBookingEvidence(UUID rentalId,UUID reservationId,String currency,Amounts agreedAmounts,
        List<PaymentRecord> payments,boolean paymentsTruncated) {
        this(rentalId,reservationId,currency,agreedAmounts,payments,paymentsTruncated,null,List.of());
    }
    public record CustomerSnapshot(String fullName,String email,String phone,String address,String identityDocumentNumber) {}
    public record GoodsItem(UUID id,String goodsCode,String categoryName,String materialName,String customGoodsName,
        String description,int quantity,BigDecimal lengthCm,BigDecimal widthCm,BigDecimal heightCm,
        BigDecimal weightPerItemKg,boolean fragile,String customerNote,List<FileAssetResponse> images) {}
    public record Amounts(BigDecimal netRentalAmount,BigDecimal reservationDepositAmount,BigDecimal securityDepositAmount,
        BigDecimal remainingRentalAmount,BigDecimal dueAtCheckIn,BigDecimal totalInitialObligation) {}
    public record PaymentRecord(UUID id,String purpose,String status,BigDecimal amount,Instant processedAt,String verification,
        String invoiceNumber, com.storagehub.domain.model.InvoiceStatus invoiceStatus, Instant invoiceIssuedAt,
        com.storagehub.service.ledger.PaymentReconciliationPreflight.Plan reconciliation,
        com.storagehub.service.ledger.SandboxPaymentRecognitionPolicy.Decision projectRecognition) {
        public PaymentRecord(UUID id,String purpose,String status,BigDecimal amount,Instant processedAt,String verification) {
            this(id,purpose,status,amount,processedAt,verification,null,null,null,null,null);
        }
    }
}
