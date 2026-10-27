package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Approval workflow for a refund; the Refund entity remains the gateway execution record. */
@Entity
@Table(name = "refund_requests")
@Getter
@Setter
@NoArgsConstructor
public class RefundRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_case_id")
    private ReturnCase returnCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RefundRequestStatus status = RefundRequestStatus.REQUESTED;

    @Column
    private Instant approvedAt;

    @Column
    private Instant rejectedAt;

    @Column
    private Instant processedAt;

    @Column(length = 1000)
    private String rejectionReason;

    @Column(length = 100)
    private String gatewayRefundId;

    @Column(length = 100)
    private String gatewayTransactionNo;

    @Column(length = 16)
    private String gatewayResponseCode;
}
