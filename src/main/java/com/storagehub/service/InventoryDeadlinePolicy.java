package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.time.BusinessTime;
import java.time.Instant;
import java.time.LocalDate;

final class InventoryDeadlinePolicy {
    private InventoryDeadlinePolicy() {}

    static void requireInventoryOpen(Rental rental, Instant now) {
        if (rental.getInventoryEscalatedAt() != null
            || expired(rental.getInventoryFinalDeadlineAt(), now)
            || expired(rental.getInventoryRemovalDeadlineAt(), now)) {
            throw ApiExceptions.conflict("Đã hết hạn kiểm kê. Vui lòng liên hệ Manager để xử lý.");
        }
    }

    static void requireRemovalDate(Rental rental, LocalDate scheduledDate, Instant now) {
        Instant deadline = rental.getInventoryRemovalDeadlineAt();
        if (deadline == null) return; // Existing cases may predate publication tracking.
        if (expired(deadline, now)) {
            throw ApiExceptions.conflict("Đã hết hạn đưa hàng ra khỏi kho. Vui lòng liên hệ cơ sở.");
        }
        if (scheduledDate == null || !scheduledDate.atStartOfDay(BusinessTime.ZONE).toInstant().isBefore(deadline)) {
            throw ApiExceptions.validation("Ngày trả kho phải nằm trong thời hạn đưa hàng ra khỏi kho.", null);
        }
    }

    private static boolean expired(Instant deadline, Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }
}
