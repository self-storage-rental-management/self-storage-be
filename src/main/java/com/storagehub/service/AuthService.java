package com.storagehub.service;

import com.storagehub.api.auth.ActorResponse;
import com.storagehub.api.auth.AuthResponse;
import com.storagehub.api.auth.ChangePasswordRequest;
import com.storagehub.api.auth.LoginRequest;
import com.storagehub.api.auth.RegisterRequest;
import com.storagehub.api.auth.RegisterResponse;
import com.storagehub.api.auth.RefreshTokenRequest;
import com.storagehub.api.auth.UpdateProfileRequest;
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
    private final AuthChallengeService authChallengeService;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
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
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setRoles(Set.of(customerRole));
        User saved = userRepository.saveAndFlush(user);

        AuthChallengeService.ChallengeIssue challenge = authChallengeService.issueEmailVerification(saved);
        ActorResponse response = actorResponseMapper.toResponse(saved);
        auditLogService.recordMutation(saved, "USER_REGISTERED", "User", saved.getId(), null, null, response);
        return new RegisterResponse(response, true, challenge.debugCode());
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = normalizeEmail(request.email());
        if (loginHistoryService.isRateLimited(email)) {
            throw ApiExceptions.unauthorized("Email or password is incorrect");
        }
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
        session.setRefreshExpiresAt(now.plus(jwtService.refreshExpirationSeconds(), ChronoUnit.SECONDS));
        session.setCreatedIp(ipAddress);
        session.setUserAgent(userAgent);
        session.setLastSeenAt(now);
        session = sessionRepository.saveAndFlush(session);

        JwtService.IssuedToken issuedToken = jwtService.issue(user, session);
        String refreshToken = jwtService.generateRefreshToken();
        session.setTokenHash(jwtService.hash(issuedToken.value()));
        session.setRefreshTokenHash(jwtService.hash(refreshToken));
        sessionRepository.saveAndFlush(session);
        loginHistoryService.record(user, email, true, ipAddress, userAgent, null);
        auditLogService.recordMutation(user, "SESSION_CREATED", "Session", session.getId(), null, null,
            Map.of("sessionId", session.getId(), "expiresAt", issuedToken.expiresAt()));

        return new AuthResponse(
            issuedToken.value(),
            "Bearer",
            jwtService.expirationSeconds(),
            session.getId().toString(),
            actorResponseMapper.toResponse(user),
            refreshToken
        );
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String tokenHash = jwtService.hash(request.refreshToken());
        Session session = sessionRepository.findByRefreshTokenHashAndRevokedAtIsNull(tokenHash)
            .orElseThrow(() -> ApiExceptions.unauthorized("The refresh token is invalid or expired"));
        Instant now = Instant.now();
        if (session.getRefreshExpiresAt() == null || !session.getRefreshExpiresAt().isAfter(now)
            || session.getUser().getStatus() != UserStatus.ACTIVE) {
            throw ApiExceptions.unauthorized("The refresh token is invalid or expired");
        }

        User user = session.getUser();
        JwtService.IssuedToken issuedToken = jwtService.issue(user, session);
        String nextRefreshToken = jwtService.generateRefreshToken();
        session.setTokenHash(jwtService.hash(issuedToken.value()));
        session.setRefreshTokenHash(jwtService.hash(nextRefreshToken));
        session.setExpiresAt(issuedToken.expiresAt());
        session.setLastSeenAt(now);
        sessionRepository.saveAndFlush(session);
        auditLogService.recordMutation(user, "SESSION_REFRESHED", "Session", session.getId(), null, null,
            Map.of("expiresAt", issuedToken.expiresAt()));

        return new AuthResponse(
            issuedToken.value(),
            "Bearer",
            jwtService.expirationSeconds(),
            session.getId().toString(),
            actorResponseMapper.toResponse(user),
            nextRefreshToken
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

    @Transactional
    public AuthResponse changePassword(ChangePasswordRequest request) {
        ActorPrincipal actor = actorContext.required();
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiExceptions.unauthorized("The current password is incorrect");
        }
        boolean wasRequiredToChangePassword = user.isMustChangePassword();
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiExceptions.validation("The new password must be different from the current password", null);
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        userRepository.saveAndFlush(user);

        Session session = sessionRepository.findByIdAndRevokedAtIsNull(actor.sessionId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The session is invalid or already revoked"));
        JwtService.IssuedToken issuedToken = jwtService.issue(user, session);
        String refreshToken = jwtService.generateRefreshToken();
        session.setTokenHash(jwtService.hash(issuedToken.value()));
        session.setRefreshTokenHash(jwtService.hash(refreshToken));
        session.setExpiresAt(issuedToken.expiresAt());
        session.setRefreshExpiresAt(Instant.now().plus(jwtService.refreshExpirationSeconds(), ChronoUnit.SECONDS));
        session.setLastSeenAt(Instant.now());
        sessionRepository.save(session);
        auditLogService.recordMutation(user, "PASSWORD_CHANGED", "User", user.getId(), null,
            Map.of("mustChangePassword", wasRequiredToChangePassword), Map.of("mustChangePassword", false));

        return new AuthResponse(
            issuedToken.value(),
            "Bearer",
            jwtService.expirationSeconds(),
            session.getId().toString(),
            actorResponseMapper.toResponse(user),
            refreshToken
        );
    }

    @Transactional(readOnly = true)
    public ActorResponse currentActor() {
        ActorPrincipal actor = actorContext.required();
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        return actorResponseMapper.toResponse(user);
    }

    @Transactional
    public ActorResponse updateCurrentActor(UpdateProfileRequest request) {
        ActorPrincipal actor = actorContext.required();
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        ActorResponse before = actorResponseMapper.toResponse(user);

        user.setFullName(requireText(request.fullName(), "fullName"));
        user.setPhone(normalizeNullable(request.phone()));
        user.setAvatarUrl(normalizeNullable(request.avatarUrl()));

        User saved = userRepository.saveAndFlush(user);
        ActorResponse response = actorResponseMapper.toResponse(saved);
        auditLogService.recordMutation(user, "USER_PROFILE_UPDATED", "User", user.getId(), null, before, response);
        return response;
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

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiExceptions.validation(field + " must not be blank", null);
        }
        return value.trim();
    }
}
