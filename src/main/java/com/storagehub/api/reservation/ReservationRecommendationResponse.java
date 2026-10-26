package com.storagehub.api.reservation;

import com.storagehub.domain.model.CompatibilityResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReservationRecommendationResponse(
    UUID facilityId,
    UUID recommendedUnitTypeId,
    String pricingPackageCode,
    String policyVersion,
    int rentalMonths,
    CompatibilityResult result,
    BigDecimal totalGoodsVolumeM3,
    BigDecimal totalGoodsWeightKg,
    long recommendedAvailableUnitCount,
    List<UUID> evaluatedUnitTypeIds,
    List<UUID> incompatibleUnitTypeIds,
    List<String> issues
) {
}
