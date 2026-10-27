package com.storagehub.time;

import java.time.ZoneId;

/** Calendar dates, opening hours and report periods use Vietnam; stored instants remain UTC. */
public final class BusinessTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private BusinessTime() {}
}
