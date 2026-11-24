package com.storagehub.api.configuration;

import java.math.BigDecimal;

public record PolicyCalculationSimulationRequest(
    BigDecimal monthlyRent,
    int rentalMonths,
    int overdueDays
) {}
