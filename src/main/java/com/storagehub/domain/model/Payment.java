package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false)
    private User initiatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renewal_id", updatable = false)
    private Renewal renewal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renewal_revision_id", updatable = false)
    private com.storagehub.service.renewal.persistence.RenewalAcceptedRevision renewalRevision;

    @Column(name = "payment_deadline", updatable = false)
    private Instant paymentDeadline;

    /** Durable work state: verified funds survive allocation/signing failures. */
    private java.util.UUID renewalEvidenceRef;
    private java.util.UUID renewalLedgerReceiptRef;
    private Instant renewalAllocatedAt;
    private Instant renewalSigningPreparedAt;
    private Instant renewalNextRetryAt;
    private int renewalReconciliationAttempts;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentType purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, unique = true, length = 100)
    private String gatewayIntentId;

    @Column(length = 32)
    private String gatewayProvider;

    @Column(length = 100)
    private String gatewayTransactionNo;

    @Column(length = 32)
    private String gatewayBankCode;

    @Column(length = 32)
    private String gatewayCardType;

    @Column(length = 16)
    private String gatewayResponseCode;

    @Column(length = 16)
    private String gatewayTransactionStatus;

    @Column(length = 32)
    private String gatewayPayDate;

    @Column
    private Instant lastReconciledAt;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_status", nullable = false, length = 16)
    private InvoiceStatus invoiceStatus = InvoiceStatus.DRAFT;

    @Column(name = "invoice_number", unique = true, length = 64)
    private String invoiceNumber;

    @Column(name = "invoice_issued_at")
    private Instant invoiceIssuedAt;

    @Column
    private Instant processedAt;

    @Column
    private Instant paidAt;

    @Column(length = 100)
    private String failureCode;

    @Column(length = 1000)
    private String failureReason;

    @Version
    private long version;

    @PrePersist
    @PreUpdate
    private void synchronizeInvoiceContract() {
        invoiceStatus = resolveInvoiceStatus();
        if ((invoiceStatus == InvoiceStatus.PAID || invoiceStatus == InvoiceStatus.REFUNDED)
            && invoiceNumber == null) {
            invoiceNumber = "INV-" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        }
        if ((invoiceStatus == InvoiceStatus.PAID || invoiceStatus == InvoiceStatus.REFUNDED)
            && invoiceIssuedAt == null) {
            invoiceIssuedAt = paidAt != null ? paidAt : Instant.now();
        }
    }

    /** Always expose the contract value even before a dirty entity is flushed. */
    public InvoiceStatus getInvoiceStatus() {
        return resolveInvoiceStatus();
    }

    private InvoiceStatus resolveInvoiceStatus() {
        if (refundedAmount != null && refundedAmount.signum() > 0) return InvoiceStatus.REFUNDED;
        if (status == PaymentStatus.PAID) return InvoiceStatus.PAID;
        if (status == PaymentStatus.FAILED || status == PaymentStatus.CANCELLED) return InvoiceStatus.VOID;
        return InvoiceStatus.DRAFT;
    }
}
