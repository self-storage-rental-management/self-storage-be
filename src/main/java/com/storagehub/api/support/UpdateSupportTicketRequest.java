package com.storagehub.api.support;

import com.storagehub.domain.model.SupportTicketStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdateSupportTicketRequest(
    @NotNull SupportTicketStatus status,
    UUID assignedToId,
    @Size(max = 4000) String message
) {}
