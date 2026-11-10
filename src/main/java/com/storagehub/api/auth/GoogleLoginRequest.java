package com.storagehub.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleLoginRequest(
    @NotBlank
    @Size(max = 8192)
    String idToken,
    @Size(max = 36) String captchaId,
    @Size(max = 6) String captchaAnswer
) {
    public GoogleLoginRequest(String idToken) { this(idToken, null, null); }
}
