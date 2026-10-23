package com.storagehub.api.credential;

import com.storagehub.domain.model.AccessCredentialType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IssueAccessCredentialRequest(
    @NotNull AccessCredentialType type,
    @NotBlank @Size(max = 160) String reference,
    @Size(max = 1000) String note
) {}
