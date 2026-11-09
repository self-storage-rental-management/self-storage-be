package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StartCheckInSetupRequest(
    @NotBlank @Size(max = 160) String lockReference,
    @Size(max = 500) String note,
    @Size(max = 1000) String initialUnitCondition,
    @Size(max = 10) List<@NotBlank @Size(max = 500) String> evidenceReferences
) {
    public StartCheckInSetupRequest(String lockReference, String note) {
        this(lockReference, note, null, null);
    }
}
