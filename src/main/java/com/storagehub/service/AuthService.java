package com.storagehub.service;

import com.storagehub.api.auth.ActorResponse;
import com.storagehub.api.auth.AuthResponse;
import com.storagehub.api.auth.ChangePasswordRequest;
import com.storagehub.api.auth.LoginRequest;
import com.storagehub.api.auth.RegisterRequest;
import com.storagehub.api.auth.RegisterResponse;
import com.storagehub.api.auth.RefreshTokenRequest;
import com.storagehub.api.auth.SessionResponse;
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
import com.storagehub.security.GoogleIdentityService;
import com.storagehub.security.JwtService;
import com.storagehub.security.GoogleIdentityService.GoogleIdentity;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private final GoogleIdentityService googleIdentityService;
    private final com.storagehub.service.email.EmailService transactionalEmailService;
    private final com.storagehub.domain.repo.LoginHistoryRepository loginHistoryRepository;
    private final com.storagehub.domain.repo.ActivityLogRepository activityLogRepository;

    @Value("${app.security.alert-cooldown-hours:6}")
    private int alertCooldownHours;

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
        user.setPermanentAddress(normalizeNullable(request.permanentAddress()));
        user.setEmergencyContactName(normalizeNullable(request.emergencyContactName()));
        user.setEmergencyContactPhone(normalizeNullable(request.emergencyContactPhone()));
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
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            handleFailedLoginAlert(user, email, ipAddress);
            throw ApiExceptions.unauthorized("Email or password is incorrect");
        }
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginHistoryService.record(user, email, false, ipAddress, userAgent, "INVALID_CREDENTIALS");
            handleFailedLoginAlert(user, email, ipAddress);
            throw ApiExceptions.unauthorized("Email or password is incorrect");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            loginHistoryService.record(user, email, false, ipAddress, userAgent, "ACCOUNT_NOT_ACTIVE");
            throw ApiExceptions.unauthorized("The account is not active");
        }

        return issueSession(user, email, ipAddress, userAgent);
    }

    @Transactional
    public AuthResponse loginWithGoogle(String idToken, String ipAddress, String userAgent) {
        GoogleIdentity identity = googleIdentityService.verify(idToken);
        User user = userRepository.findByEmailIgnoreCase(identity.email()).orElse(null);
        if (user == null) {
            Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER)
                .orElseThrow(() -> ApiExceptions.conflict("System roles have not been initialized"));
            user = new User();
            user.setEmail(identity.email());
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setFullName(limitName(identity.name(), identity.email()));
            user.setAvatarUrl(limitAvatar(identity.picture()));
            user.setStatus(UserStatus.ACTIVE);
            user.setRoles(Set.of(customerRole));
            user = userRepository.saveAndFlush(user);
            auditLogService.recordMutation(
                user,
                "USER_REGISTERED_GOOGLE",
                "User",
                user.getId(),
                null,
                null,
                actorResponseMapper.toResponse(user)
            );
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            loginHistoryService.record(user, identity.email(), false, ipAddress, userAgent, "ACCOUNT_NOT_ACTIVE");
            throw ApiExceptions.unauthorized("The account is not active");
        }
        return issueSession(user, identity.email(), ipAddress, userAgent);
    }

    private AuthResponse issueSession(User user, String email, String ipAddress, String userAgent) {
        String safeUserAgent = (userAgent != null && userAgent.length() > 512) ? userAgent.substring(0, 512) : userAgent;
        handleNewLoginNotice(user, ipAddress, safeUserAgent);

        Instant now = Instant.now();
        Session session = new Session();
        session.setUser(user);
        session.setExpiresAt(now.plus(jwtService.expirationSeconds(), ChronoUnit.SECONDS));
        session.setRefreshExpiresAt(now.plus(jwtService.refreshExpirationSeconds(), ChronoUnit.SECONDS));
        session.setCreatedIp(ipAddress);
        session.setUserAgent(safeUserAgent);
        session.setLastSeenAt(now);
        session = sessionRepository.saveAndFlush(session);

        JwtService.IssuedToken issuedToken = jwtService.issue(user, session);
        String refreshToken = jwtService.generateRefreshToken();
        session.setTokenHash(jwtService.hash(issuedToken.value()));
        session.setRefreshTokenHash(jwtService.hash(refreshToken));
        sessionRepository.saveAndFlush(session);
        loginHistoryService.record(user, email, true, ipAddress, safeUserAgent, null);
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

    private void handleNewLoginNotice(User user, String ipAddress, String userAgent) {
        try {
            boolean hasPriorSuccess = loginHistoryRepository.existsByUserIdAndSuccessTrue(user.getId());
            if (!hasPriorSuccess) {
                return;
            }

            com.storagehub.service.email.UserAgentParser.ClientInfo clientInfo =
                com.storagehub.service.email.UserAgentParser.parse(userAgent);
            String fingerprint = clientInfo.fingerprint();

            boolean knownFingerprint = loginHistoryRepository.existsByUserIdAndSuccessTrueAndDeviceFingerprint(user.getId(), fingerprint);
            if (!knownFingerprint) {
                // Check if user has legacy successful logins with null fingerprint whose userAgent matches this fingerprint
                List<com.storagehub.domain.model.LoginHistory> legacyLogins =
                    loginHistoryRepository.findByUserIdAndSuccessTrueAndDeviceFingerprintIsNull(user.getId());
                for (com.storagehub.domain.model.LoginHistory legacy : legacyLogins) {
                    if (legacy.getUserAgent() != null && com.storagehub.service.email.UserAgentParser.parse(legacy.getUserAgent()).fingerprint().equals(fingerprint)) {
                        knownFingerprint = true;
                        legacy.setDeviceFingerprint(fingerprint);
                        loginHistoryRepository.save(legacy);
                        break;
                    }
                }
            }
            if (knownFingerprint) {
                return;
            }

            String loginTime = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
                .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(Instant.now());

            transactionalEmailService.sendNewLoginNotice(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                loginTime,
                clientInfo.device(),
                clientInfo.browser(),
                "Không xác định",
                ipAddress != null ? ipAddress : "Không xác định",
                null
            );
        } catch (Exception ex) {
            log.warn("Failed to process new-login email notice: {}", ex.getMessage());
        }
    }

    private void handleFailedLoginAlert(User user, String email, String ipAddress) {
        try {
            if (user == null) {
                return;
            }

            Instant windowStart = Instant.now().minus(15, ChronoUnit.MINUTES);
            long failedCount = loginHistoryRepository.countByEmailAttemptedAndSuccessFalseAndOccurredAtAfter(
                email,
                windowStart
            );

            if (failedCount < 5) {
                return;
            }

            Instant cooldownStart = Instant.now().minus(alertCooldownHours, ChronoUnit.HOURS);
            boolean alreadyAlerted = activityLogRepository.existsByActionAndEntityIdAndCreatedAtAfter(
                "SECURITY_ALERT_EMAIL_SENT",
                user.getId(),
                cooldownStart
            );
            if (alreadyAlerted) {
                return;
            }

            boolean isLocked = (user.getStatus() == UserStatus.LOCKED);
            Instant now = Instant.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
                .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
            String lastAttemptTime = formatter.format(now);
            String unlockTime = formatter.format(now.plus(15, ChronoUnit.MINUTES));

            transactionalEmailService.sendSecurityAlert(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                (int) failedCount,
                15,
                unlockTime,
                lastAttemptTime,
                ipAddress != null ? ipAddress : "Không xác định",
                "Không xác định",
                null,
                isLocked
            );
        } catch (Exception ex) {
            log.warn("Failed to process security-alert email notice: {}", ex.getMessage());
        }
    }

    private String limitName(String name, String email) {
        String fallback = email.substring(0, email.indexOf('@'));
        String value = name == null || name.isBlank() ? fallback : name.trim();
        return value.substring(0, Math.min(value.length(), 160));
    }

    private String limitAvatar(String picture) {
        if (picture == null || picture.isBlank() || picture.length() > 2048) return null;
        return picture;
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
        boolean isCustomer = user.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.CUSTOMER);
        if (!isCustomer && !user.isMustChangePassword()) {
            throw ApiExceptions.forbidden("Only customer accounts can change their password");
        }
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

        String changeTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.now());
        String deviceSummary = com.storagehub.service.email.UserAgentParser.parse(session.getUserAgent()).summary();
        transactionalEmailService.sendAccountChanged(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            changeTime,
            "Mật khẩu",
            "••••••••",
            "••••••••",
            false,
            deviceSummary,
            session.getCreatedIp(),
            null
        );

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

    @Transactional(readOnly = true)
    public List<SessionResponse> currentSessions() {
        ActorPrincipal actor = actorContext.required();
        Instant now = Instant.now();
        return sessionRepository.search(
                actor.userId(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"))
            )
            .getContent()
            .stream()
            .map(session -> new SessionResponse(
                session.getId(),
                session.getCreatedIp(),
                session.getUserAgent(),
                session.getCreatedAt(),
                session.getLastSeenAt(),
                session.getExpiresAt(),
                session.getRevokedAt(),
                session.isActive(now)
            ))
            .toList();
    }

    @Transactional
    public ActorResponse updateCurrentActor(UpdateProfileRequest request) {
        ActorPrincipal actor = actorContext.required();
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        ActorResponse before = actorResponseMapper.toResponse(user);

        user.setFullName(requireText(request.fullName(), "fullName"));
        if (request.phone() != null) {
            user.setPhone(normalizeNullable(request.phone()));
        }
        if (request.permanentAddress() != null) {
            user.setPermanentAddress(normalizeNullable(request.permanentAddress()));
        }
        if (request.emergencyContactName() != null) {
            user.setEmergencyContactName(normalizeNullable(request.emergencyContactName()));
        }
        if (request.emergencyContactPhone() != null) {
            user.setEmergencyContactPhone(normalizeNullable(request.emergencyContactPhone()));
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(normalizeNullable(request.avatarUrl()));
        }

        User saved = userRepository.saveAndFlush(user);
        ActorResponse response = actorResponseMapper.toResponse(saved);
        auditLogService.recordMutation(user, "USER_PROFILE_UPDATED", "User", user.getId(), null, before, response);

        if (!java.util.Objects.equals(before.phone(), saved.getPhone())) {
            String changeTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
                .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(Instant.now());
            transactionalEmailService.sendAccountChanged(
                saved.getId(),
                saved.getEmail(),
                saved.getFullName(),
                changeTime,
                "Số điện thoại",
                before.phone() != null ? before.phone() : "Chưa thiết lập",
                saved.getPhone() != null ? saved.getPhone() : "Đã xóa",
                false,
                "Không xác định",
                "Không xác định",
                null
            );
        }
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
