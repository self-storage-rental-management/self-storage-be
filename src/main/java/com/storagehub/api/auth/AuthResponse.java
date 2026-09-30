package com.storagehub.api.auth;

public record AuthResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    String sessionId,
    ActorResponse actor,
    String refreshToken
) {
}
