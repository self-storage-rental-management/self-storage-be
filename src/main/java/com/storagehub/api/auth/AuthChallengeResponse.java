package com.storagehub.api.auth;

public record AuthChallengeResponse(
    boolean accepted,
    boolean verificationRequired,
    String debugCode
) {
}
