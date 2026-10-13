package com.storagehub.api.configuration;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UnitTypePricingRequest(
    @NotNull @DecimalMin("0.01") BigDecimal monthlyPrice,
    @NotNull @DecimalMin("0.00") BigDecimal securityDepositAmount
) {}
