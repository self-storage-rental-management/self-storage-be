package com.storagehub.service;

import com.storagehub.api.auth.AuthChallengeResponse;
import com.storagehub.api.auth.ForgotPasswordRequest;
import com.storagehub.api.auth.PasswordResetResponse;
import com.storagehub.api.auth.ResetPasswordRequest;
import com.storagehub.api.auth.VerifyEmailRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.AuthChallenge;
import com.storagehub.domain.model.AuthChallengePurpose;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.AuthChallengeRepository;
import com.storagehub.domain.repo.SessionRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthChallengeService {

    private static final int MAX_ATTEMPTS = 5;

    private final AuthChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final com.storagehub.service.email.EmailService transactionalEmailService;
    private final ActorResponseMapper actorResponseMapper;
    private final AuditLogService auditLogService;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.auth.verification-url:http://localhost:5173/?verifyEmail=}")
    private String verificationUrl;

    @Value("${app.auth.password-reset-url:http://localhost:5173/?resetPassword=}")
    private String passwordResetUrl;

    @Value("${app.auth.challenge-expiry-minutes:15}")
    private int expiryMinutes;

    @Value("${app.auth.expose-development-code:false}")
    private boolean exposeDevelopmentCode;

    @Transactional
    public ChallengeIssue issueEmailVerification(User user) {
        ChallengeIssue issue = issue(user, AuthChallengePurpose.EMAIL_VERIFICATION);
        transactionalEmailService.sendVerifyEmail(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            verificationUrl + issue.token(),
            expiryMinutes
        );
        return issue;
    }

    @Transactional
    public AuthChallengeResponse requestPasswordReset(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || user.getStatus() == UserStatus.DISABLED || user.getStatus() == UserStatus.LOCKED) {
            return new AuthChallengeResponse(true, true, null);
        }
        ChallengeIssue issue = issue(user, AuthChallengePurpose.PASSWORD_RESET);
        transactionalEmailService.sendPasswordReset(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            passwordResetUrl + issue.token(),
            expiryMinutes
        );
        return new AuthChallengeResponse(true, true, issue.debugCode());
    }

    @Transactional
    public com.storagehub.api.auth.ActorResponse verifyEmail(VerifyEmailRequest request) {
        User user;
        AuthChallenge challenge;
        if (request.email() == null || request.email().isBlank()) {
            challenge = challengeRepository.findByTokenHashAndPurposeAndConsumedAtIsNull(
                    jwtService.hash(request.codeOrToken()), AuthChallengePurpose.EMAIL_VERIFICATION)
                .orElseThrow(this::invalidChallenge);
            user = challenge.getUser();
        } else {
            user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(this::invalidChallenge);
            challenge = latest(user, AuthChallengePurpose.EMAIL_VERIFICATION);
        }
        verifyCredential(challenge, request.codeOrToken());
        Instant now = Instant.now();
        challenge.setConsumedAt(now);
        user.setStatus(UserStatus.ACTIVE);
        challengeRepository.save(challenge);
        User saved = userRepository.saveAndFlush(user);
        auditLogService.recordMutation(saved, "EMAIL_VERIFIED", "User", saved.getId(), null,
            java.util.Map.of("status", UserStatus.PENDING_VERIFICATION),
            java.util.Map.of("status", UserStatus.ACTIVE));
        return actorResponseMapper.toResponse(saved);
    }

    @Transactional
    public PasswordResetResponse resetPassword(ResetPasswordRequest request) {
        User user;
        AuthChallenge challenge;
        if (request.email() == null || request.email().isBlank()) {
            challenge = challengeRepository.findByTokenHashAndPurposeAndConsumedAtIsNull(
                    jwtService.hash(request.codeOrToken()), AuthChallengePurpose.PASSWORD_RESET)
                .orElseThrow(this::invalidChallenge);
            user = challenge.getUser();
        } else {
            user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(this::invalidChallenge);
            challenge = latest(user, AuthChallengePurpose.PASSWORD_RESET);
        }
        verifyCredential(challenge, request.codeOrToken());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        challenge.setConsumedAt(Instant.now());
        challengeRepository.save(challenge);
        User saved = userRepository.saveAndFlush(user);
        sessionRepository.revokeActiveByUserId(saved.getId(), Instant.now());
        auditLogService.recordMutation(saved, "PASSWORD_RESET", "User", saved.getId(), null,
            java.util.Map.of("passwordChanged", false), java.util.Map.of("passwordChanged", true));
        String changeTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.now());
        transactionalEmailService.sendAccountChanged(
            saved.getId(),
            saved.getEmail(),
            saved.getFullName(),
            changeTime,
            "Mật khẩu",
            "••••••••",
            "••••••••",
            false,
            "Không xác định",
            "Không xác định",
            null
        );
        return new PasswordResetResponse(true);
    }

    private ChallengeIssue issue(User user, AuthChallengePurpose purpose) {
        Instant now = Instant.now();
        challengeRepository.consumeActiveByUserAndPurpose(user.getId(), purpose, now);
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        String token = jwtService.generateRefreshToken();
        AuthChallenge challenge = new AuthChallenge();
        challenge.setUser(user);
        challenge.setPurpose(purpose);
        challenge.setOtpHash(jwtService.hash(otp));
        challenge.setTokenHash(jwtService.hash(token));
        challenge.setExpiresAt(now.plus(15, ChronoUnit.MINUTES));
        challenge.setFailedAttempts(0);
        challengeRepository.saveAndFlush(challenge);
        boolean allowDebugCode = exposeDevelopmentCode && isDevOrLocal();
        return new ChallengeIssue(otp, token, allowDebugCode ? otp : null);
    }

    private boolean isDevOrLocal() {
        if (environment == null) return false;
        return Arrays.stream(environment.getActiveProfiles())
            .anyMatch(p -> p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("local"));
    }

    private AuthChallenge latest(User user, AuthChallengePurpose purpose) {
        return challengeRepository.findTopByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId(), purpose)
            .orElseThrow(this::invalidChallenge);
    }

    private void verifyCredential(AuthChallenge challenge, String credential) {
        if (!challenge.isUsable(Instant.now())) {
            throw invalidChallenge();
        }
        String hash = jwtService.hash(credential.trim());
        boolean matches = constantTimeEquals(hash, challenge.getOtpHash())
            || constantTimeEquals(hash, challenge.getTokenHash());
        if (!matches) {
            challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
            if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
                challenge.setConsumedAt(Instant.now());
            }
            challengeRepository.saveAndFlush(challenge);
            throw invalidChallenge();
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private com.storagehub.common.api.ApiException invalidChallenge() {
        return ApiExceptions.unauthorized("The verification code or reset token is invalid or expired");
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record ChallengeIssue(String otp, String token, String debugCode) {
    }
}
