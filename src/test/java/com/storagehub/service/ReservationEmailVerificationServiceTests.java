package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.ReservationEmailVerificationRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationEmailVerification;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ReservationEmailVerificationRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.security.JwtService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationEmailVerificationServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationEmailVerificationRepository verificationRepository;
    @Mock private JwtService jwtService;
    @Mock private AuthEmailService emailService;
    @Mock private AuditLogService auditLogService;

    private ReservationEmailVerificationService service;
    private ActorPrincipal actor;
    private Reservation reservation;
    private ReservationEmailVerification verification;

    @BeforeEach
    void setUp() {
        service = new ReservationEmailVerificationService(
            reservationRepository, verificationRepository, jwtService, emailService, auditLogService
        );

        User customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        customer.setEmail("customer@example.com");
        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());

        reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setReservationCode("RSV-VERIFY001");
        reservation.setStatus(ReservationStatus.AWAITING_EMAIL);
        reservation.setGoodsReviewStatus(GoodsReviewStatus.NOT_REQUIRED);
        reservation.setHoldExpiresAt(Instant.now().plusSeconds(600));

        actor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );

        verification = new ReservationEmailVerification();
        verification.setReservation(reservation);
        verification.setOtpHash("correct-hash");
        verification.setExpiresAt(Instant.now().plusSeconds(600));
        verification.setLastSentAt(Instant.now().minusSeconds(61));
    }

    @Test
    void verifiesCodeAndMovesReservationToPayment() {
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(verification));
        when(jwtService.hash("123456")).thenReturn("correct-hash");

        var response = service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("123456")
        );

        assertThat(response.isVerified()).isTrue();
        assertThat(response.getReservationStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        assertThat(verification.getVerifiedAt()).isNotNull();
        assertThat(reservation.getPaymentExpiresAt()).isBetween(
            Instant.now().plus(23, ChronoUnit.HOURS), Instant.now().plus(25, ChronoUnit.HOURS)
        );
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    void movesReservationToReviewWhenGoodsNeedReview() {
        reservation.setGoodsReviewStatus(GoodsReviewStatus.PENDING);
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(verification));
        when(jwtService.hash("123456")).thenReturn("correct-hash");

        var response = service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("123456")
        );

        assertThat(response.getReservationStatus()).isEqualTo(ReservationStatus.AWAITING_REVIEW);
    }

    @Test
    void increasesFailedAttemptsForWrongCode() {
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(verification));
        when(jwtService.hash("999999")).thenReturn("wrong-hash");

        assertThatThrownBy(() -> service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("999999")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("invalid or expired");
        assertThat(verification.getFailedAttempts()).isEqualTo(1);
        verify(verificationRepository).saveAndFlush(verification);
    }

    @Test
    void rejectsExpiredCode() {
        verification.setExpiresAt(Instant.now().minusSeconds(1));
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("123456")
        ))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(401))
            .hasMessageContaining("invalid or expired");
    }

    @Test
    void rejectsCodeAfterFiveFailedAttempts() {
        verification.setFailedAttempts(5);
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("123456")
        ))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(401))
            .hasMessageContaining("invalid or expired");
    }

    @Test
    void rejectsReusingCodeAfterSuccessfulVerification() {
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        verification.setVerifiedAt(Instant.now().minusSeconds(1));
        mockOwnedReservation();

        assertThatThrownBy(() -> service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("123456")
        ))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(409))
            .hasMessage("Reservation is not waiting for email verification");
    }

    @Test
    void rejectsResendDuringCooldown() {
        verification.setLastSentAt(Instant.now().minusSeconds(10));
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.resend(actor, reservation.getId()))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(409))
            .hasMessage("Please wait 60 seconds before requesting another code");
    }

    @Test
    void invalidatesOldCodeAfterResend() {
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(verification));
        when(jwtService.hash(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return "123456".equals(value) ? "old-hash" : "new-hash";
        });
        when(verificationRepository.saveAndFlush(any(ReservationEmailVerification.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.resend(actor, reservation.getId());
        assertThat(verification.getOtpHash()).isEqualTo("new-hash");

        assertThatThrownBy(() -> service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("123456")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("invalid or expired");
        assertThat(verification.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void createsAndSendsNewCode() {
        mockOwnedReservation();
        when(verificationRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.empty());
        when(jwtService.hash(any())).thenReturn("generated-hash");
        when(verificationRepository.saveAndFlush(any(ReservationEmailVerification.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.resend(actor, reservation.getId());

        assertThat(response.isVerified()).isFalse();
        assertThat(response.getExpiresAt()).isAfter(Instant.now());
        verify(emailService).sendReservationVerification(any(), any());
    }

    @Test
    void rejectsVerificationWhenReservationDoesNotBelongToCustomer() {
        when(reservationRepository.findByIdAndCustomer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(
            actor, reservation.getId(), new ReservationEmailVerificationRequest("123456")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Reservation was not found");
    }

    private void mockOwnedReservation() {
        when(reservationRepository.findByIdAndCustomer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
    }
}
