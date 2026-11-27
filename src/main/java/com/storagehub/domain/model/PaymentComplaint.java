package com.storagehub.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payment_complaints", uniqueConstraints =
    @UniqueConstraint(name = "uk_payment_complaint_reservation", columnNames = "reservation_id"))
@Getter @Setter @NoArgsConstructor
public class PaymentComplaint extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentComplaintStatus status = PaymentComplaintStatus.PENDING;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false)
    private Instant submittedAt;

    @Column(nullable = false)
    private Instant reviewDueAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private Instant reviewedAt;
    private Instant withdrawnAt;

    @Column(length = 2000)
    private String decisionReason;

    @Version
    private long version;
}
