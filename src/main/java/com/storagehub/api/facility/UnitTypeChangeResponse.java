package com.storagehub.api.facility;

import java.time.Instant;
import java.util.UUID;

public record UnitTypeChangeResponse(UUID id, UUID unitTypeId, UUID facilityId, String facilityName,
    String unitTypeCode, UUID requestedBy, String requesterName, Instant createdAt, String status,
    ManagerUnitTypeRequest before, ManagerUnitTypeRequest after, UUID reviewedBy, Instant reviewedAt,
    String rejectionReason, boolean requiresRepricing, java.math.BigDecimal currentMonthlyPrice,
    java.math.BigDecimal currentSecurityDepositAmount) {}
