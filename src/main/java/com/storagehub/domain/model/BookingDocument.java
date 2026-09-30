package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "booking_documents")
@Getter
@Setter
@NoArgsConstructor
public class BookingDocument extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_asset_id", nullable = false, unique = true)
    private FileAsset fileAsset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private BookingDocumentType documentType = BookingDocumentType.BOOKING_CONFIRMATION;

    @Column(nullable = false)
    private Instant issuedAt;
}
