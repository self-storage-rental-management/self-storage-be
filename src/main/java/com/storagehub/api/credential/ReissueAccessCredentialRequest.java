package com.storagehub.api.credential;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Input for replacing one active credential while its rental is still active. */
public record ReissueAccessCredentialRequest(
    @NotNull UUID credentialId,
    @NotNull @AssertTrue(message = "identityVerified must be true") Boolean identityVerified,
    @NotBlank @Pattern(regexp = "ID_DOCUMENT|CUSTOMER_ACCOUNT|AUTHORIZED_REPRESENTATIVE|OTHER") String verificationMethod,
    @NotBlank @Size(max = 1000) String reason,
    @NotBlank @Size(max = 160) String reference,
    @Size(max = 200) String secret,
    @Size(max = 1000) String note
) {}
