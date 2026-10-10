package com.storagehub.api.rental;

import com.storagehub.domain.model.RentalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Rental detail; absent/incomplete owner sources remain UNKNOWN/null. No raw credential is exposed.")
public record RentalDetailResponse(UUID id, RentalSummaryResponse.Customer customer,
    RentalSummaryResponse.Facility facility, RentalSummaryResponse.Unit storageUnit,
    RentalSummaryResponse.UnitType unitType, RentalStatus status, LocalDate startDate,
    LocalDate contractEndDate, BigDecimal monthlyPrice, String currency,
    List<RentalSummaryResponse.Warning> dataWarnings, UUID reservationId,
    Instant actualReturnedAt, Instant completedAt, Financial financialSummary, Access access,
    RentalSummaryResponse.DateSemantics dateSemantics) {
    @Schema(description = "COMPLETE includes verified security deposit; PARTIAL has verified balances/billing but unknown deposit; UNKNOWN has no verified financial projection.")
    public record Financial(@Schema(allowableValues = {"COMPLETE", "PARTIAL", "UNKNOWN"}) String completeness, String currency, BigDecimal outstandingAmount,
        BigDecimal overdueAmount, LocalDate nextDueDate, String reason,
        BigDecimal securityDepositAmount, String billingMode) {}
    public record Access(String completeness, String status, String reason) {}
}
