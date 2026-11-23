package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
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
}
