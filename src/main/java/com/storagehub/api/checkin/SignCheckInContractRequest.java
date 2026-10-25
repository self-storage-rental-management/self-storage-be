package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SignCheckInContractRequest(
    @NotNull UUID contractEvidenceFileId
) {
}
