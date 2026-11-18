package com.storagehub.api.reservation;

import java.math.BigDecimal;

/** Giới hạn lớn nhất thực tế trong các loại kho đã được kiểm tra. */
public record ReservationStorageLimits(
    BigDecimal maxCargoLengthCm,
    BigDecimal maxCargoWidthCm,
    BigDecimal maxCargoHeightCm,
    BigDecimal maxCargoWeightKg
) {}
