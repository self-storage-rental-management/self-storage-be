package com.storagehub.api.returns;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record StaffReturnSettlementRequest(@NotBlank String paymentMethod,
    @NotBlank @Size(max=120) String transactionReference,@NotNull UUID receiptFileId,
    @NotNull Boolean confirmed, @NotNull @DecimalMin("0.01") java.math.BigDecimal amount) {}
