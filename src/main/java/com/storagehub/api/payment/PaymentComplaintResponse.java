package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentComplaintStatus;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.storagehub.api.reservation.ReservationGoodsItemResponse;

public record PaymentComplaintResponse(
    UUID id, UUID reservationId, String reservationCode, UUID customerId,
    UUID facilityId, UUID unitTypeId, LocalDate startDate, LocalDate endDate,
    BigDecimal depositAmount, BigDecimal netRentalAmount,
    List<ReservationGoodsItemResponse> goodsItems, PaymentComplaintStatus status,
    PaymentStatus paymentStatus, ReservationStatus reservationStatus,
    String reason, List<UUID> imageIds, List<PaymentComplaintImageResponse> images,
    Instant submittedAt, Instant reviewDueAt,
    Instant reviewedAt, Instant withdrawnAt, String decisionReason
) {}
