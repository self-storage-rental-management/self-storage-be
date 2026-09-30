package com.storagehub.api.auth;

public record RegisterResponse(
    ActorResponse actor,
    boolean verificationRequired,
    String debugCode
) {
}
