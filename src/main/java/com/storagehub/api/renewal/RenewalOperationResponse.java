package com.storagehub.api.renewal;

import java.time.Instant;
import java.util.*;

public record RenewalOperationResponse(UUID renewalId,Long expectedVersion,String phase,
    UUID depositPaymentRef,Instant depositPaidAt,Instant originalSigningDeadline,Instant effectiveSigningDeadline,
    Instant recoveryCutoff,UUID appointmentRef,UUID arrivalRef,UUID confirmedExceptionRef,
    UUID completedBy,Instant completedAt,List<String> missingSources,
    Instant appointmentStart,Instant appointmentEnd,UUID pendingExceptionRef) {
    public record Event(UUID id,String kind,Instant occurredAt,UUID actorId,Object data) {}
}
