package com.storagehub.api.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateSupportTicketRequest(
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 4000) String description,
    UUID facilityId
) {}
