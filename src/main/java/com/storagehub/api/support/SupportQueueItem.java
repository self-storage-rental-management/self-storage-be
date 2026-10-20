package com.storagehub.api.support;

import java.time.Instant;
import java.util.UUID;

/** Safe unassigned-work summary; conversation and linked evidence require a successful claim. */
public record SupportQueueItem(UUID id, UUID facilityId, String subject, Instant createdAt, Long version, String displayCode) {
    public SupportQueueItem(UUID id,UUID facilityId,String subject,Instant createdAt,Long version) {this(id,facilityId,subject,createdAt,version,null);}
}
