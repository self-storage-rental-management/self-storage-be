package com.storagehub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiErrorWriter;
import com.storagehub.common.api.ErrorCode;
import com.storagehub.domain.model.Session;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.SessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
            Jwt jwt = authentication.getToken();
            try {
                UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
                UUID userId = UUID.fromString(jwt.getSubject());
                Session session = sessionRepository.findByIdAndRevokedAtIsNull(sessionId).orElse(null);
                if (session == null || !session.isActive(Instant.now()) || !session.getUser().getId().equals(userId)
                    || session.getUser().getStatus() != UserStatus.ACTIVE) {
                    SecurityContextHolder.clearContext();
                    ApiErrorWriter.write(response, objectMapper, 401, ErrorCode.UNAUTHORIZED, "The session is invalid or expired");
                    return;
                }
                if (session.getUser().isMustChangePassword() && !isPasswordChangeRequest(request)) {
                    ApiErrorWriter.write(response, objectMapper, 403, ErrorCode.FORBIDDEN,
                        "Password change is required before using this resource");
                    return;
                }
            } catch (IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                ApiErrorWriter.write(response, objectMapper, 401, ErrorCode.UNAUTHORIZED, "The session claim is invalid");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isPasswordChangeRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
            && "/api/auth/password".equals(request.getServletPath());
    }
}
