package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Monotonic completed-renewal counter retained even if a historical renewal is removed. */
@Entity
@Table(name = "renewal_display_sequences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RenewalDisplaySequence {
    @Id
    @Column(name = "rental_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID rentalId;

    @Column(name = "last_sequence", nullable = false)
    private int lastSequence;

    public RenewalDisplaySequence(UUID rentalId, int lastSequence) {
        if (rentalId == null || lastSequence < 0) throw new IllegalArgumentException("Invalid renewal display sequence");
        this.rentalId = rentalId;
        this.lastSequence = lastSequence;
    }

    public void advanceTo(int value) {
        if (value <= lastSequence) throw new IllegalArgumentException("Renewal display sequence must advance");
        lastSequence = value;
    }
}
