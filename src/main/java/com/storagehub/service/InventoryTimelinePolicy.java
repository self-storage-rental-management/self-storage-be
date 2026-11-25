package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.UnitType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Calendar boundaries are exclusive, so every allowed final date is usable in full. */
public final class InventoryTimelinePolicy {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final String RETURN_WINDOW_POLICY_VERSION = "INVENTORY_MISMATCH_RETURN_BY_UNIT_SIZE_V1";
    public static final int MAX_RETURN_EXTENSIONS = 2;
    private InventoryTimelinePolicy() {}

    public static Instant dayStart(Instant event, int day) {
        return event.atZone(ZONE).toLocalDate().plusDays(day).atStartOfDay(ZONE).toInstant();
    }

    public static int returnWindowDaysForUnitType(UnitType unitType) {
        return returnWindowPolicyForUnitType(unitType)[0];
    }

    public static int returnExtensionDaysForUnitType(UnitType unitType) {
        return returnWindowPolicyForUnitType(unitType)[1];
    }

    private static int[] returnWindowPolicyForUnitType(UnitType unitType) {
        String code = unitType == null ? null : unitType.getCode();
        if (code == null || code.isBlank()) {
            throw ApiExceptions.conflict("Cỡ kho chưa được cấu hình hạn trả kho do kiểm kê sai lệch. Vui lòng cấu hình cỡ kho trước khi áp dụng.");
        }
        return switch (code.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "S" -> new int[]{3, 2};
            case "M" -> new int[]{5, 3};
            case "L" -> new int[]{7, 4};
            case "XL" -> new int[]{10, 5};
            default -> throw ApiExceptions.conflict(
                "Cỡ kho \"" + code.trim() + "\" chưa được cấu hình hạn trả kho do kiểm kê sai lệch. Vui lòng cấu hình trước khi áp dụng.");
        };
    }

    public static Instant reminderAt(Instant ready) { return dayStart(ready, 4); }
    public static Instant inventoryDeadline(Instant ready) { return dayStart(ready, 8); }
    public static Instant removalEffectiveAt(Instant published) { return dayStart(published, 1); }
    public static Instant removalDeadline(Instant published) { return dayStart(published, 8); }
    public static Instant removalDeadline(Instant published, int returnWindowDays) {
        return dayStart(published, 1 + returnWindowDays);
    }

    public static void initializeRemovalWindow(Rental rental, Instant publishedAt) {
        if (rental == null || publishedAt == null || rental.getInventoryRemovalDeadlineAt() != null) {
            return;
        }
        UnitType unitType = rental.getStorageUnit() == null ? null : rental.getStorageUnit().getUnitType();
        int[] policy = returnWindowPolicyForUnitType(unitType);
        rental.setInventoryRemovalPublishedAt(publishedAt);
        rental.setInventoryRemovalDeadlineAt(removalDeadline(publishedAt, policy[0]));
        rental.setInventoryReturnWindowDays(policy[0]);
        rental.setInventoryReturnExtensionDays(policy[1]);
        rental.setInventoryReturnExtensionCount(0);
        rental.setInventoryReturnPolicyVersion(RETURN_WINDOW_POLICY_VERSION);
    }

    public static boolean canExtendRemovalWindow(Rental rental, Instant now) {
        return rental != null
            && rental.getInventoryRemovalPublishedAt() != null
            && rental.getInventoryRemovalDeadlineAt() != null
            && rental.getInventoryEscalatedAt() == null
            && now != null
            && now.isBefore(rental.getInventoryRemovalDeadlineAt())
            && rental.getInventoryReturnWindowDays() != null
            && rental.getInventoryReturnExtensionDays() != null
            && rental.getInventoryReturnExtensionDays() > 0
            && rental.getInventoryReturnExtensionCount() < MAX_RETURN_EXTENSIONS;
    }

    public static Instant extendRemovalWindow(Rental rental, Instant now) {
        if (rental == null || rental.getInventoryRemovalPublishedAt() == null || rental.getInventoryRemovalDeadlineAt() == null) {
            throw ApiExceptions.conflict("Hồ sơ chưa mở thời hạn trả kho do kiểm kê sai lệch.");
        }
        if (rental.getInventoryEscalatedAt() != null || !now.isBefore(rental.getInventoryRemovalDeadlineAt())) {
            throw ApiExceptions.conflict("Đã hết thời hạn trả kho, không thể gia hạn thêm.");
        }
        if (rental.getInventoryReturnWindowDays() == null || rental.getInventoryReturnExtensionDays() == null
            || rental.getInventoryReturnExtensionDays() <= 0) {
            throw ApiExceptions.conflict("Hồ sơ này không hỗ trợ gia hạn thêm thời gian trả kho.");
        }
        if (rental.getInventoryReturnExtensionCount() >= MAX_RETURN_EXTENSIONS) {
            throw ApiExceptions.conflict("Bạn đã sử dụng hết số lần gia hạn trả kho cho phép.");
        }
        int nextCount = rental.getInventoryReturnExtensionCount() + 1;
        Instant nextDeadline = rental.getInventoryRemovalDeadlineAt().atZone(ZONE)
            .plusDays(rental.getInventoryReturnExtensionDays()).toInstant();
        int nextWindowDays = Math.toIntExact(ChronoUnit.DAYS.between(
            removalEffectiveAt(rental.getInventoryRemovalPublishedAt()).atZone(ZONE).toLocalDate(),
            nextDeadline.atZone(ZONE).toLocalDate()));
        rental.setInventoryReturnExtensionCount(nextCount);
        rental.setInventoryReturnWindowDays(nextWindowDays);
        rental.setInventoryRemovalDeadlineAt(nextDeadline);
        return nextDeadline;
    }

    public static Integer resolveReturnWindowDays(Rental rental, ReturnCase returnCase) {
        if (returnCase != null && returnCase.getReturnWindowDays() != null) {
            return returnCase.getReturnWindowDays();
        }
        if (rental != null && rental.getInventoryReturnWindowDays() != null) {
            return rental.getInventoryReturnWindowDays();
        }
        if (rental != null && rental.getInventoryRemovalPublishedAt() != null && rental.getInventoryRemovalDeadlineAt() != null) {
            long days = ChronoUnit.DAYS.between(
                removalEffectiveAt(rental.getInventoryRemovalPublishedAt()).atZone(ZONE).toLocalDate(),
                rental.getInventoryRemovalDeadlineAt().atZone(ZONE).toLocalDate()
            );
            return days > 0 ? Math.toIntExact(days) : null;
        }
        return null;
    }
}
