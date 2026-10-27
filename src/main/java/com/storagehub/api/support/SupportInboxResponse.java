package com.storagehub.api.support;

import com.storagehub.common.api.PageResponse;
import java.time.Instant;
import java.util.List;

/** Staff rows are grouped by Customer and Facility without merging stored conversations. */
public record SupportInboxResponse(List<Row> data, PageResponse.Pagination pagination, String correlationId, Counts counts) {
    public enum View { queue, mine, all, history }
    public record Counts(long queue, long mine, long all, long history) {}
    public record Row(SupportResponse ticket, SupportQueueItem queued, long historyCount, Instant lastActivityAt) {}
}
