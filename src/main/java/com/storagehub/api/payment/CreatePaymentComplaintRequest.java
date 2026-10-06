package com.storagehub.api.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreatePaymentComplaintRequest(
    @NotBlank @Size(max = 2000) String reason,
    @NotEmpty @Size(max = 10) List<UUID> imageIds
) {}
