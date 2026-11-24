package com.storagehub.api.configuration;

import java.math.BigDecimal;

public record PolicyCalculationSimulationResponse(
    BigDecimal monthlyRent,
    int rentalMonths,
    BigDecimal totalRentValue,

    // Deposit Simulation
    String depositCalculationMethod,
    BigDecimal calculatedDepositAmount,
    String depositFormulaDescription,

    // Late Penalty Simulation
    int overdueDays,
    int gracePeriodDays,
    int chargeableOverdueDays,
    String latePenaltyMethod,
    BigDecimal calculatedPenaltyAmount,
    String penaltyFormulaDescription
) {}
