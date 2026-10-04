package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentComplaintStatus;
import com.storagehub.domain.model.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ManagerPaymentQueueItemResponse(
    UUID reservationId,
    String reservationCode,
    UUID customerId,
    String customerEmail,
    UUID facilityId,
    UUID unitTypeId,
    LocalDate startDate,
    LocalDate endDate,
    ReservationStatus reservationStatus,
    BigDecimal depositAmount,
    UUID complaintId,
    PaymentComplaintStatus complaintStatus,
    String complaintReason,
    Instant complaintExpiresAt,
    Instant reviewDueAt,
    int priority,
    Instant createdAt
) {}
