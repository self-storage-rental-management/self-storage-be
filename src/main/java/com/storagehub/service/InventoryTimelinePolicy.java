package com.storagehub.service;

import java.time.Instant;
import java.time.ZoneId;

/** Calendar boundaries are exclusive, so every allowed final date is usable in full. */
public final class InventoryTimelinePolicy {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private InventoryTimelinePolicy() {}

    public static Instant dayStart(Instant event, int day) {
        return event.atZone(ZONE).toLocalDate().plusDays(day).atStartOfDay(ZONE).toInstant();
    }

    public static Instant reminderAt(Instant ready) { return dayStart(ready, 4); }
    public static Instant inventoryDeadline(Instant ready) { return dayStart(ready, 8); }
    public static Instant removalEffectiveAt(Instant published) { return dayStart(published, 1); }
    public static Instant removalDeadline(Instant published) { return dayStart(published, 8); }
}
