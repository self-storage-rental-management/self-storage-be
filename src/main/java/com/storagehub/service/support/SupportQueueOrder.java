package com.storagehub.service.support;

import com.storagehub.api.support.SupportResponse;
import com.storagehub.domain.model.SupportTicketStatus;
import java.time.Instant;
import java.util.Comparator;

final class SupportQueueOrder {
    private SupportQueueOrder() {}
    static Comparator<SupportResponse> order(SupportService.Audience audience) {
        return Comparator.comparingInt((SupportResponse r) -> group(r, audience))
            .thenComparing(r -> group(r, audience) == 2 ? r.closedAt() != null ? r.closedAt() : r.resolvedAt() : null,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparingInt(r -> r.sla() != null && "HIGH".equals(r.sla().priority()) ? 0 : 1)
            .thenComparing(SupportQueueOrder::due, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(r -> r.acceptedAt() != null ? r.acceptedAt() : r.assignedAt() != null ? r.assignedAt() : r.createdAt(),
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SupportResponse::id);
    }
    private static int group(SupportResponse r, SupportService.Audience audience) {
        if (r.status() == SupportTicketStatus.resolved || r.status() == SupportTicketStatus.closed) return 2;
        boolean customerTurn = r.status() == SupportTicketStatus.waiting_customer;
        return (audience == SupportService.Audience.CUSTOMER ? customerTurn : !customerTurn) ? 0 : 1;
    }
    private static Instant due(SupportResponse r) {
        if (r.sla() == null || r.sla().activeWorkPaused()) return null;
        var first = r.sla().firstReplyDueAt(); var next = r.sla().nextUpdateDueAt();
        return first == null ? next : next == null || first.isBefore(next) ? first : next;
    }
}
