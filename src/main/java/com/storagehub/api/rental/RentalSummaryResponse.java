package com.storagehub.api.rental;

import com.storagehub.domain.model.RentalStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Read-only rental record. monthlyPrice is the historical applied VND rate, not today's catalog price.")
public record RentalSummaryResponse(UUID id, Customer customer, Facility facility,
    Unit storageUnit, UnitType unitType, RentalStatus status, LocalDate startDate,
    LocalDate contractEndDate, BigDecimal monthlyPrice, String currency, List<Warning> dataWarnings,
    DateSemantics dateSemantics) {
    public record Customer(UUID id, String fullName) {}
    public record Facility(UUID id, String code, String name) {}
    public record Unit(UUID id, String code) {}
    public record UnitType(UUID id, String code, String name) {}
    public record Warning(String field, String reason) {}
    @Schema(description = "Canonical dates are additive. contractEndDate is still the unchanged stored date; UNKNOWN has null canonical dates.")
    public record DateSemantics(String completeness, String convention, LocalDate lastPermittedDate, LocalDate endExclusive) {}
}
