package com.storagehub.api.renewal;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

public record RenewalQuoteResponse(UUID id,UUID rentalId,UUID customerId,@com.fasterxml.jackson.annotation.JsonUnwrapped Terms terms,Instant quotedAt,Instant expiresAt) {
    public record Terms(LocalDate oldEndDate,LocalDate extensionStartDate,LocalDate extensionEndExclusive,LocalDate newEndDate,
        UUID unitTypeId,String pricingPackageCode,UUID packagePolicyRef,String packagePolicyVersion,int rentalMonths,
        BigDecimal monthlyPrice,BigDecimal discountRate,BigDecimal subtotal,BigDecimal discountAmount,BigDecimal totalAfterDiscount,
        BigDecimal renewalDepositAmount,BigDecimal remainingRentalAmount,String currency,String renewalPolicyRef,String renewalPolicyVersion,
        UUID storageUnitId,UUID facilityId) {}
}
