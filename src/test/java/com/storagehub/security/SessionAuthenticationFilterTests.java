package com.storagehub.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.Session;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.SessionRepository;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SessionAuthenticationFilterTests {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsTokenAfterTheUserAuthorizationVersionChanges() throws Exception {
        var userId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var user = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        user.setStatus(UserStatus.ACTIVE);
        user.setAuthzVersion(2);
        var session = new Session();
        org.springframework.test.util.ReflectionTestUtils.setField(session, "id", sessionId);
        session.setUser(user);
        session.setExpiresAt(Instant.now().plusSeconds(300));
        SessionRepository sessions = (SessionRepository) Proxy.newProxyInstance(
            SessionRepository.class.getClassLoader(),
            new Class<?>[] {SessionRepository.class},
            (proxy, method, args) -> method.getName().equals("findByIdAndRevokedAtIsNull")
                ? Optional.of(session)
                : defaultValue(method.getReturnType())
        );
        ObjectMapper objectMapper = new ObjectMapper();
        var chainCalled = new boolean[] {false};
        var chain = (jakarta.servlet.FilterChain) (request, response) -> chainCalled[0] = true;
        var request = new MockHttpServletRequest("GET", "/api/profile");
        var response = new MockHttpServletResponse();

        Jwt jwt = Jwt.withTokenValue("stale-token")
            .header("alg", "HS256")
            .subject(userId.toString())
            .claim("sid", sessionId.toString())
            .claim("authzVersion", 1L)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        new SessionAuthenticationFilter(sessions, objectMapper).doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled[0]).isFalse();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0F;
        if (returnType == double.class) return 0D;
        if (returnType == char.class) return '\0';
        return null;
    }
}
