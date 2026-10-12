package com.storagehub.api.reservation;

import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.ReservationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StaffReservationProgressResponse(
    UUID reservationId,
    String reservationCode,
    UUID customerId,
    String customerName,
    String customerEmail,
    UUID facilityId,
    String facilityName,
    UUID unitTypeId,
    String unitTypeName,
    UUID assignedUnitId,
    String assignedUnitCode,
    ReservationStatus status,
    GoodsReviewStatus goodsReviewStatus,
    LocalDate startDate,
    LocalDate endDate,
    Instant appointmentAt,
    Instant holdExpiresAt,
    Instant paymentExpiresAt,
    Instant createdAt
) {
}
