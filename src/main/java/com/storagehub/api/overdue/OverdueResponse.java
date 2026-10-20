package com.storagehub.api.overdue;

import com.storagehub.common.api.PageResponse;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record OverdueResponse(String caseRef,UUID rentalId,UUID facilityId,UUID customerId,String customerName,
    String storageUnitCode,String kind,long overdueDays,String priority,UUID obligationRef,
    Instant dueAt,BigDecimal outstanding,String currency,String policyRef,String policyVersion,
    Instant recoveryCutoff,boolean recoveryEligible,long followUpVersion,String workflowStatus,UUID recoveryTaskId) {
    public OverdueResponse(String caseRef,UUID rentalId,UUID facilityId,UUID customerId,String customerName,
        String storageUnitCode,String kind,long overdueDays,String priority,UUID obligationRef,
        Instant dueAt,BigDecimal outstanding,String currency,String policyRef,String policyVersion,
        Instant recoveryCutoff,boolean recoveryEligible,long followUpVersion) {
        this(caseRef,rentalId,facilityId,customerId,customerName,storageUnitCode,kind,overdueDays,priority,obligationRef,
            dueAt,outstanding,currency,policyRef,policyVersion,recoveryCutoff,recoveryEligible,followUpVersion,null,null);
    }
    public record ListResult(List<OverdueResponse> data,PageResponse.Pagination pagination,String correlationId,
        Instant asOf,String completeness,List<String> missingSources) {}
    public record FollowUp(UUID id,String caseRef,String type,String content,UUID actorId,Instant recordedAt,
        UUID externalRef,String policyRef,String policyVersion,long followUpVersion) {}
}
