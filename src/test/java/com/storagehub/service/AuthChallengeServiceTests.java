package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.auth.ResendEmailVerificationRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.AuthChallenge;
import com.storagehub.domain.model.AuthChallengePurpose;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.AuthChallengeRepository;
import com.storagehub.domain.repo.SessionRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.JwtService;
import com.storagehub.service.email.EmailService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthChallengeServiceTests {

    @Mock private AuthChallengeRepository challengeRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private EmailService emailService;
    @Mock private ActorResponseMapper actorResponseMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private Environment environment;

    private AuthChallengeService service;
    private User pendingUser;

    @BeforeEach
    void setUp() {
        service = new AuthChallengeService(
            challengeRepository, userRepository, sessionRepository, passwordEncoder, jwtService,
            emailService, actorResponseMapper, auditLogService, environment
        );
        ReflectionTestUtils.setField(service, "exposeDevelopmentCode", true);
        ReflectionTestUtils.setField(service, "verificationUrl", "http://localhost:5173/?verifyEmail=");
        ReflectionTestUtils.setField(service, "expiryMinutes", 15);
        pendingUser = new User();
        ReflectionTestUtils.setField(pendingUser, "id", UUID.randomUUID());
        pendingUser.setEmail("pending@storagehub.test");
        pendingUser.setStatus(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void resendsVerificationForPendingAccountAndInvalidatesOldChallenge() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"local"});
        when(userRepository.findByEmailIgnoreCase("pending@storagehub.test"))
            .thenReturn(Optional.of(pendingUser));
        when(challengeRepository.findTopByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            pendingUser.getId(), AuthChallengePurpose.EMAIL_VERIFICATION
        )).thenReturn(Optional.empty());
        when(jwtService.generateRefreshToken()).thenReturn("new-token");
        when(jwtService.hash(any())).thenAnswer(invocation -> "hash-" + invocation.getArgument(0));
        when(challengeRepository.saveAndFlush(any(AuthChallenge.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.resendEmailVerification(
            new ResendEmailVerificationRequest(" Pending@StorageHub.Test ")
        );

        assertThat(response.accepted()).isTrue();
        assertThat(response.verificationRequired()).isTrue();
        assertThat(response.debugCode()).matches("\\d{6}");
        verify(challengeRepository).consumeActiveByUserAndPurpose(
            eq(pendingUser.getId()), eq(AuthChallengePurpose.EMAIL_VERIFICATION), any(Instant.class)
        );
        verify(emailService).sendVerifyEmail(
            eq(pendingUser.getId()), eq(pendingUser.getEmail()), eq(pendingUser.getFullName()),
            eq("http://localhost:5173/?verifyEmail=new-token"), eq(15)
        );
    }

    @Test
    void rejectsResendDuringCooldown() {
        AuthChallenge challenge = new AuthChallenge();
        ReflectionTestUtils.setField(challenge, "createdAt", Instant.now().minusSeconds(10));
        when(userRepository.findByEmailIgnoreCase("pending@storagehub.test"))
            .thenReturn(Optional.of(pendingUser));
        when(challengeRepository.findTopByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            pendingUser.getId(), AuthChallengePurpose.EMAIL_VERIFICATION
        )).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.resendEmailVerification(
            new ResendEmailVerificationRequest("pending@storagehub.test")
        ))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(409))
            .hasMessage("Please wait 60 seconds before requesting another code");
        verify(emailService, never()).sendVerifyEmail(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void returnsGenericResponseForUnknownAccount() {
        when(userRepository.findByEmailIgnoreCase("missing@storagehub.test"))
            .thenReturn(Optional.empty());

        var response = service.resendEmailVerification(
            new ResendEmailVerificationRequest("missing@storagehub.test")
        );

        assertThat(response.accepted()).isTrue();
        assertThat(response.debugCode()).isNull();
        verify(emailService, never()).sendVerifyEmail(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void returnsGenericResponseForAlreadyActiveAccount() {
        pendingUser.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByEmailIgnoreCase("pending@storagehub.test"))
            .thenReturn(Optional.of(pendingUser));

        var response = service.resendEmailVerification(
            new ResendEmailVerificationRequest("pending@storagehub.test")
        );

        assertThat(response.accepted()).isTrue();
        assertThat(response.debugCode()).isNull();
        verify(emailService, never()).sendVerifyEmail(any(), any(), any(), any(), any(Integer.class));
    }
}
