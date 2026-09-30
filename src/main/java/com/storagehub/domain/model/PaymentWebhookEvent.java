package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "payment_webhook_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_webhook_provider_event",
        columnNames = {"provider", "provider_event_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class PaymentWebhookEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(nullable = false, length = 160)
    private String providerEventId;

    @Column(nullable = false, length = 10000)
    private String payload;

    @Column
    private Instant processedAt;

    @Column(length = 1000)
    private String processingError;
}
