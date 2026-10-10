package com.storagehub.security;

import com.storagehub.config.JwtProperties;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.Session;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final UserFacilityScopeRepository scopeRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public IssuedToken issue(User user, Session session) {
        Map<java.util.UUID, FacilityScopeLevel> scopes = scopeRepository.findByUserId(user.getId()).stream()
            .collect(Collectors.toMap(scope -> scope.getFacility().getId(), scope -> scope.getScopeLevel()));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getExpiration(), ChronoUnit.SECONDS);
        List<String> roles = user.getRoles().stream().map(role -> role.getCode().name()).sorted().toList();
        List<String> permissions = user.getRoles().stream()
            .flatMap(role -> role.getEffectivePermissions().stream())
            .map(permission -> permission.getCode())
            .distinct()
            .sorted()
            .toList();
        List<String> encodedScopes = scopes.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue().name())
            .sorted()
            .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("storagehub")
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .id(session.getId().toString())
            .claim("sid", session.getId().toString())
            .claim("roles", roles)
            .claim("permissions", permissions)
            .claim("mustChangePassword", user.isMustChangePassword())
            .claim("facilityIds", scopes.keySet().stream().map(Object::toString).sorted().toList())
            .claim("facilityScopes", encodedScopes)
            .build();

        String token = jwtEncoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)
        ).getTokenValue();
        return new IssuedToken(token, expiresAt, scopes);
    }

    public String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public long expirationSeconds() {
        return properties.getExpiration();
    }

    public long refreshExpirationSeconds() {
        return properties.getRefreshExpiration();
    }

    public String generateRefreshToken() {
        byte[] value = new byte[48];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record IssuedToken(String value, Instant expiresAt, Map<java.util.UUID, FacilityScopeLevel> facilityScopes) {
    }
}
