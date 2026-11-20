package com.storagehub.api.reservation;

/** Trạng thái tổng hợp dùng để chọn đúng màn hình kết quả đặt kho. */
public enum ReservationRecommendationStatus {
    FIT,
    ALTERNATIVE_AVAILABLE,
    NO_COMPATIBLE_WAREHOUSE,
    NO_VACANT_WAREHOUSE,
    REVIEW_REQUIRED
}
