package com.storagehub.api.returns;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record BusinessLegacyPricingDecisionRequest(
    @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2)
    BigDecimal approvedRentalCharge,
    @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2)
    BigDecimal approvedRentalRefund,
    @NotBlank @Size(max = 2000)
    String resolutionNote,
    @NotBlank @Size(max = 12000)
    String pricingSnapshotJson,
    @NotNull
    UUID sourceDocumentFileId
) {}
