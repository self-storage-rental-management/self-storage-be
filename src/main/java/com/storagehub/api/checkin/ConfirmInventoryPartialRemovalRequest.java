package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ConfirmInventoryPartialRemovalRequest(
    Boolean remainderRemoved,
    @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 200) String> evidenceReferences,
    @NotBlank @Size(max = 2000) String notes
) {}
