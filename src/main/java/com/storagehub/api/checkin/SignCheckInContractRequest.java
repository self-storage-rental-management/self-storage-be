package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SignCheckInContractRequest(
    @NotNull UUID contractEvidenceFileId,
    @jakarta.validation.constraints.Size(max = 50) String confirmedSetupEarlyExitPolicyVersion
) {
    public SignCheckInContractRequest(UUID contractEvidenceFileId) { this(contractEvidenceFileId, null); }
}
