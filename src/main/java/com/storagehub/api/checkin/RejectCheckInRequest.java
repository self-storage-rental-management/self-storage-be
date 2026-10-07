package com.storagehub.api.checkin;

import com.storagehub.domain.model.UnitReleaseDisposition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RejectCheckInRequest(
    @NotBlank @Size(max = 1000) String reason,
    @NotNull UnitReleaseDisposition disposition,
    @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 500) String> evidenceReferences
) {
}
