package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerPaymentReceiptResponse(
    UUID paymentId,
    UUID reservationId,
    String transactionReference,
    String gatewayTransactionNo,
    BigDecimal amount,
    String currency,
    BigDecimal refundedAmount,
    PaymentStatus paymentStatus,
    PaymentType purpose,
    ReservationStatus reservationStatus,
    String responseCode,
    String transactionStatus,
    String gatewayPayDate,
    String paymentMethod,
    String message,
    Instant processedAt,
    Instant paidAt,
    Instant lastReconciledAt,
    String initiatedByName,
    Instant createdAt,
    String reservationCode,
    String invoiceNumber,
    InvoiceStatus invoiceStatus,
    Instant invoiceIssuedAt,
    String facilityCode,
    String facilityName,
    String facilityAddress,
    String unitTypeName,
    String assignedUnitCode,
    String receiptCode,
    UUID receiptFileId
) {
    public CustomerPaymentReceiptResponse(
    UUID paymentId,
    UUID reservationId,
    String transactionReference,
    String gatewayTransactionNo,
    BigDecimal amount,
    String currency,
    BigDecimal refundedAmount,
    PaymentStatus paymentStatus,
    PaymentType purpose,
    ReservationStatus reservationStatus,
    String responseCode,
    String transactionStatus,
    String gatewayPayDate,
    String paymentMethod,
    String message,
    Instant processedAt,
    Instant paidAt,
    Instant lastReconciledAt,
    String initiatedByName,
    Instant createdAt,
    String reservationCode,
    String invoiceNumber,
    InvoiceStatus invoiceStatus,
    Instant invoiceIssuedAt,
    String facilityCode,
    String facilityName,
    String facilityAddress,
    String unitTypeName,
    String assignedUnitCode,
    String receiptCode
    ) {
        this(paymentId, reservationId, transactionReference, gatewayTransactionNo, amount, currency, refundedAmount, paymentStatus, purpose, reservationStatus, responseCode, transactionStatus, gatewayPayDate, paymentMethod, message, processedAt, paidAt, lastReconciledAt, initiatedByName, createdAt, reservationCode, invoiceNumber, invoiceStatus, invoiceIssuedAt, facilityCode, facilityName, facilityAddress, unitTypeName, assignedUnitCode, receiptCode, null);
    }

    public CustomerPaymentReceiptResponse(UUID paymentId,
    UUID reservationId,
    String transactionReference,
    String gatewayTransactionNo,
    BigDecimal amount,
    String currency,
    BigDecimal refundedAmount,
    PaymentStatus paymentStatus,
    PaymentType purpose,
    ReservationStatus reservationStatus,
    String responseCode,
    String transactionStatus,
    String gatewayPayDate,
    String paymentMethod,
    String message,
    Instant processedAt,
    Instant paidAt,
    Instant lastReconciledAt,
    String initiatedByName,
    Instant createdAt,
    String reservationCode,
    String invoiceNumber,
    InvoiceStatus invoiceStatus,
    Instant invoiceIssuedAt,
    String facilityCode,
    String facilityName,
    String facilityAddress,
    String unitTypeName,
    String assignedUnitCode) {
        this(paymentId, reservationId, transactionReference, gatewayTransactionNo, amount, currency, refundedAmount, paymentStatus, purpose, reservationStatus, responseCode, transactionStatus, gatewayPayDate, paymentMethod, message, processedAt, paidAt, lastReconciledAt, initiatedByName, createdAt, reservationCode, invoiceNumber, invoiceStatus, invoiceIssuedAt, facilityCode, facilityName, facilityAddress, unitTypeName, assignedUnitCode, null);
    }

}
