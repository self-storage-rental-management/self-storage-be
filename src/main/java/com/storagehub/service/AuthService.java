package com.storagehub.service;

import com.storagehub.api.auth.ActorResponse;
import com.storagehub.api.auth.AuthResponse;
import com.storagehub.api.auth.LoginRequest;
import com.storagehub.api.auth.RegisterRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.Session;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.SessionRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.security.JwtService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ActorContext actorContext;
    private final ActorResponseMapper actorResponseMapper;
    private final LoginHistoryService loginHistoryService;
    private final AuditLogService auditLogService;

    @Transactional
    public ActorResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiExceptions.conflict("An account with this email already exists");
        }

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER)
            .orElseThrow(() -> ApiExceptions.conflict("System roles have not been initialized"));
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName().trim());
        user.setPhone(normalizeNullable(request.phone()));
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(customerRole));
        User saved = userRepository.saveAndFlush(user);

        ActorResponse response = actorResponseMapper.toResponse(saved);
        auditLogService.recordMutation(saved, "USER_REGISTERED", "User", saved.getId(), null, null, response);
        return response;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginHistoryService.record(user, email, false, ipAddress, userAgent, "INVALID_CREDENTIALS");
            throw ApiExceptions.unauthorized("Email or password is incorrect");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            loginHistoryService.record(user, email, false, ipAddress, userAgent, "ACCOUNT_NOT_ACTIVE");
            throw ApiExceptions.unauthorized("The account is not active");
        }

        Instant now = Instant.now();
        Session session = new Session();
        session.setUser(user);
        session.setExpiresAt(now.plus(jwtService.expirationSeconds(), ChronoUnit.SECONDS));
        session.setCreatedIp(ipAddress);
        session.setUserAgent(userAgent);
        session.setLastSeenAt(now);
        session = sessionRepository.saveAndFlush(session);

        JwtService.IssuedToken issuedToken = jwtService.issue(user, session);
        session.setTokenHash(jwtService.hash(issuedToken.value()));
        sessionRepository.saveAndFlush(session);
        loginHistoryService.record(user, email, true, ipAddress, userAgent, null);
        auditLogService.recordMutation(user, "SESSION_CREATED", "Session", session.getId(), null, null,
            Map.of("sessionId", session.getId(), "expiresAt", issuedToken.expiresAt()));

        return new AuthResponse(
            issuedToken.value(),
            "Bearer",
            jwtService.expirationSeconds(),
            session.getId().toString(),
            actorResponseMapper.toResponse(user)
        );
    }

    @Transactional
    public void logout() {
        ActorPrincipal actor = actorContext.required();
        Session session = sessionRepository.findByIdAndRevokedAtIsNull(actor.sessionId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The session is invalid or already revoked"));
        if (!session.getUser().getId().equals(actor.userId())) {
            throw ApiExceptions.unauthorized("The session actor is invalid");
        }
        Instant revokedAt = Instant.now();
        session.setRevokedAt(revokedAt);
        sessionRepository.save(session);
        auditLogService.recordMutation(session.getUser(), "SESSION_REVOKED", "Session", session.getId(), null,
            Map.of("active", true), Map.of("active", false, "revokedAt", revokedAt));
    }

    @Transactional(readOnly = true)
    public ActorResponse currentActor() {
        ActorPrincipal actor = actorContext.required();
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        return actorResponseMapper.toResponse(user);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
