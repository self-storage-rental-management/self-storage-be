package com.storagehub.security;

import com.storagehub.common.api.ApiExceptions;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdentityService {

    private final JwtDecoder googleJwtDecoder;

    public GoogleIdentityService(@Qualifier("googleJwtDecoder") JwtDecoder googleJwtDecoder) {
        this.googleJwtDecoder = googleJwtDecoder;
    }

    public GoogleIdentity verify(String idToken) {
        try {
            Jwt token = googleJwtDecoder.decode(idToken);
            String subject = token.getSubject();
            String email = token.getClaimAsString("email");
            Boolean emailVerified = token.getClaimAsBoolean("email_verified");
            if (subject == null || subject.isBlank() || email == null || email.isBlank() || !Boolean.TRUE.equals(emailVerified)) {
                throw ApiExceptions.unauthorized("Google account email is not verified");
            }
            String name = token.getClaimAsString("name");
            String picture = token.getClaimAsString("picture");
            return new GoogleIdentity(
                subject,
                email.trim().toLowerCase(Locale.ROOT),
                name == null ? "" : name.trim(),
                picture == null ? null : picture.trim()
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw ApiExceptions.unauthorized("Google identity token is invalid");
        }
    }

    public record GoogleIdentity(String subject, String email, String name, String picture) {
    }
}
