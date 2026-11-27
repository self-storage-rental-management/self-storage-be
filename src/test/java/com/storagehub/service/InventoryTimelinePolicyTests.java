package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.UnitType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InventoryTimelinePolicyTests {
    @Test void preservesSevenFullCalendarDaysAcrossMonthBoundary() {
        Instant notice = Instant.parse("2026-12-28T16:45:00Z");
        assertThat(InventoryTimelinePolicy.removalEffectiveAt(notice))
            .isEqualTo(Instant.parse("2026-12-28T17:00:00Z"));
        assertThat(InventoryTimelinePolicy.removalDeadline(notice))
            .isEqualTo(Instant.parse("2027-01-04T17:00:00Z"));
    }

    @Test void remindsAtStartOfFourthDayAndRecoversAtStartOfEighth() {
        Instant ready = Instant.parse("2026-11-21T08:00:00Z");
        assertThat(InventoryTimelinePolicy.reminderAt(ready))
            .isEqualTo(Instant.parse("2026-11-24T17:00:00Z"));
        assertThat(InventoryTimelinePolicy.inventoryDeadline(ready))
            .isEqualTo(Instant.parse("2026-11-28T17:00:00Z"));
    }

    @Test void mapsReturnWindowDaysByUnitTypeAndRejectsUnconfiguredSize() {
        assertThat(InventoryTimelinePolicy.returnWindowDaysForUnitType(unitType("S"))).isEqualTo(3);
        assertThat(InventoryTimelinePolicy.returnWindowDaysForUnitType(unitType("M"))).isEqualTo(5);
        assertThat(InventoryTimelinePolicy.returnWindowDaysForUnitType(unitType("L"))).isEqualTo(7);
        assertThat(InventoryTimelinePolicy.returnWindowDaysForUnitType(unitType("XL"))).isEqualTo(10);

        assertThatThrownBy(() -> InventoryTimelinePolicy.returnWindowDaysForUnitType(unitType("XXL")))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> InventoryTimelinePolicy.returnWindowDaysForUnitType(null))
            .isInstanceOf(RuntimeException.class);
    }

    @Test void initializesReturnWindowSnapshotAndPreservesLegacyDeadline() {
        Instant publishedAt = Instant.parse("2026-11-24T08:00:00Z");
        Rental smallRental = rentalWithSize("S");
        InventoryTimelinePolicy.initializeRemovalWindow(smallRental, publishedAt);
        assertThat(smallRental.getInventoryReturnWindowDays()).isEqualTo(3);
        assertThat(smallRental.getInventoryReturnPolicyVersion()).isEqualTo(InventoryTimelinePolicy.RETURN_WINDOW_POLICY_VERSION);
        assertThat(smallRental.getInventoryRemovalDeadlineAt()).isEqualTo(InventoryTimelinePolicy.removalDeadline(publishedAt, 3));

        Rental xlRental = rentalWithSize("XL");
        InventoryTimelinePolicy.initializeRemovalWindow(xlRental, publishedAt);
        assertThat(xlRental.getInventoryReturnWindowDays()).isEqualTo(10);
        assertThat(xlRental.getInventoryRemovalDeadlineAt()).isEqualTo(InventoryTimelinePolicy.removalDeadline(publishedAt, 10));

        // Legacy rental already has a 7-day deadline and no snapshot: must not be altered retroactively.
        Rental legacyRental = rentalWithSize("S");
        Instant legacyPublishedAt = Instant.parse("2026-11-20T08:00:00Z");
        Instant legacyDeadline = InventoryTimelinePolicy.removalDeadline(legacyPublishedAt, 7);
        legacyRental.setInventoryRemovalPublishedAt(legacyPublishedAt);
        legacyRental.setInventoryRemovalDeadlineAt(legacyDeadline);

        InventoryTimelinePolicy.initializeRemovalWindow(legacyRental, publishedAt);
        assertThat(legacyRental.getInventoryRemovalDeadlineAt()).isEqualTo(legacyDeadline);
        assertThat(InventoryTimelinePolicy.resolveReturnWindowDays(legacyRental, new ReturnCase())).isEqualTo(7);
    }

    @Test void handlesYearBoundaryForXlTenDaysAndPreservesSnapshotAfterConfigChange() {
        // Announced on 29/12/2026 15:00 ICT (08:00 UTC) -> effective 30/12 00:00 ICT,
        // 10 calendar days = 30/12..08/01, recovery boundary = 09/01 00:00 ICT (08/01 17:00 UTC).
        Instant dec29 = Instant.parse("2026-12-29T08:00:00Z");
        Rental xlRental = rentalWithSize("XL");
        InventoryTimelinePolicy.initializeRemovalWindow(xlRental, dec29);
        assertThat(InventoryTimelinePolicy.removalEffectiveAt(dec29))
            .isEqualTo(Instant.parse("2026-12-29T17:00:00Z"));
        assertThat(xlRental.getInventoryReturnWindowDays()).isEqualTo(10);
        assertThat(xlRental.getInventoryRemovalDeadlineAt())
            .isEqualTo(Instant.parse("2027-01-08T17:00:00Z"));

        // Changing the unit type later does not mutate the already-snapshotted rental window.
        Rental smallRental = rentalWithSize("S");
        InventoryTimelinePolicy.initializeRemovalWindow(smallRental, dec29);
        assertThat(smallRental.getInventoryReturnWindowDays()).isEqualTo(3);
        Instant smallDeadline = smallRental.getInventoryRemovalDeadlineAt();
        smallRental.getStorageUnit().getUnitType().setCode("XL");
        InventoryTimelinePolicy.initializeRemovalWindow(smallRental, Instant.parse("2026-12-30T08:00:00Z"));
        assertThat(smallRental.getInventoryReturnWindowDays()).isEqualTo(3);
        assertThat(smallRental.getInventoryRemovalDeadlineAt()).isEqualTo(smallDeadline);
    }

    @Test void extendsRemovalWindowStepwiseUpToTwoTimesPerUnitSize() {
        Instant publishedAt = Instant.parse("2026-11-20T08:00:00Z");

        // XL: 10 -> +2 = 12 -> +2 = 14 (3rd extension blocked)
        Rental xl = rentalWithSize("XL");
        InventoryTimelinePolicy.initializeRemovalWindow(xl, publishedAt);
        assertThat(xl.getInventoryReturnWindowDays()).isEqualTo(10);
        assertThat(xl.getInventoryReturnExtensionDays()).isEqualTo(2);
        assertThat(xl.getInventoryReturnExtensionCount()).isZero();
        assertThat(InventoryTimelinePolicy.canExtendRemovalWindow(xl, publishedAt.plusSeconds(60))).isTrue();

        InventoryTimelinePolicy.extendRemovalWindow(xl, publishedAt.plusSeconds(60));
        assertThat(xl.getInventoryReturnExtensionCount()).isEqualTo(1);
        assertThat(xl.getInventoryReturnWindowDays()).isEqualTo(12);
        assertThat(xl.getInventoryRemovalDeadlineAt()).isEqualTo(InventoryTimelinePolicy.removalDeadline(publishedAt, 12));

        InventoryTimelinePolicy.extendRemovalWindow(xl, publishedAt.plusSeconds(120));
        assertThat(xl.getInventoryReturnExtensionCount()).isEqualTo(2);
        assertThat(xl.getInventoryReturnWindowDays()).isEqualTo(14);
        assertThat(xl.getInventoryRemovalDeadlineAt()).isEqualTo(InventoryTimelinePolicy.removalDeadline(publishedAt, 14));
        assertThat(InventoryTimelinePolicy.canExtendRemovalWindow(xl, publishedAt.plusSeconds(180))).isFalse();
        assertThatThrownBy(() -> InventoryTimelinePolicy.extendRemovalWindow(xl, publishedAt.plusSeconds(180)))
            .hasMessageContaining("hết số lần gia hạn");

        // S: 3 -> 5 -> 7; M: 5 -> 7 -> 9; L: 7 -> 9 -> 11
        assertExtensionProgression("S", publishedAt, 3, 5, 7);
        assertExtensionProgression("M", publishedAt, 5, 7, 9);
        assertExtensionProgression("L", publishedAt, 7, 9, 11);
    }

    private static void assertExtensionProgression(String size, Instant publishedAt, int d0, int d1, int d2) {
        Rental r = rentalWithSize(size);
        InventoryTimelinePolicy.initializeRemovalWindow(r, publishedAt);
        assertThat(r.getInventoryReturnWindowDays()).isEqualTo(d0);
        InventoryTimelinePolicy.extendRemovalWindow(r, publishedAt.plusSeconds(60));
        assertThat(r.getInventoryReturnWindowDays()).isEqualTo(d1);
        InventoryTimelinePolicy.extendRemovalWindow(r, publishedAt.plusSeconds(120));
        assertThat(r.getInventoryReturnWindowDays()).isEqualTo(d2);
    }

    @Test void extendsFromCurrentApprovedDeadline() {
        Instant published = Instant.parse("2026-11-25T10:00:00Z");
        Rental rental = rentalWithSize("S");
        InventoryTimelinePolicy.initializeRemovalWindow(rental, published);
        Instant approved = InventoryTimelinePolicy.removalDeadline(published, 6);
        rental.setInventoryRemovalDeadlineAt(approved);
        assertThat(InventoryTimelinePolicy.extendRemovalWindow(rental, published.plusSeconds(60)))
            .isEqualTo(InventoryTimelinePolicy.dayStart(approved, 2));
        assertThat(InventoryTimelinePolicy.resolveReturnWindowDays(rental, null)).isEqualTo(8);
    }

    @Test void invalidLegacyWindowDoesNotBecomeSevenDays() {
        Rental rental = rentalWithSize("S");
        Instant published = Instant.parse("2026-11-25T10:00:00Z");
        rental.setInventoryRemovalPublishedAt(published);
        rental.setInventoryRemovalDeadlineAt(published);
        assertThat(InventoryTimelinePolicy.resolveReturnWindowDays(rental, null)).isNull();
    }

    private static UnitType unitType(String code) {
        UnitType type = new UnitType();
        type.setCode(code);
        return type;
    }

    private static Rental rentalWithSize(String code) {
        StorageUnit unit = new StorageUnit();
        unit.setUnitType(unitType(code));
        Rental rental = new Rental();
        rental.setStorageUnit(unit);
        return rental;
    }
}
