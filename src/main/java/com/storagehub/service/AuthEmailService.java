package com.storagehub.service;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.User;
import com.storagehub.service.email.TransactionalEmailService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthEmailService {
    private final TransactionalEmailService mail;

    @Value("${app.auth.verification-url:http://localhost:5173/?verifyEmail=}")
    private String verificationUrl;
    @Value("${app.auth.password-reset-url:http://localhost:5173/?resetPassword=}")
    private String passwordResetUrl;
    @Value("${app.auth.challenge-expiry-minutes:5}")
    private int expiryMinutes = 5;

    public void sendVerification(User user, String otp, String token) {
        mail.sendVerifyEmailAsync(user.getId(), user.getEmail(), user.getFullName(), verificationUrl + token, expiryMinutes, otp);
    }

    public void sendPasswordReset(User user, String otp, String token) {
        mail.sendPasswordResetAsync(user.getId(), user.getEmail(), user.getFullName(), passwordResetUrl + token, expiryMinutes, otp);
    }

    /** Compatibility for the legacy reservation state; new bookings use account verification. */
    public void sendReservationVerification(Reservation reservation, String otp) {
        User customer = reservation.getCustomer();
        Instant expiry = Instant.now().plusSeconds(600);
        if (reservation.getHoldExpiresAt() != null && reservation.getHoldExpiresAt().isBefore(expiry)) {
            expiry = reservation.getHoldExpiresAt();
        }
        Map<String, Object> variables = Map.of(
            "fullName", customer.getFullName(), "reservationCode", reservation.getReservationCode(),
            "otp", otp, "expiryMinutes", 10, "_expiresAt", expiry.toString());
        mail.queueTemplate(customer.getId(), customer.getEmail(), "StorageHub - Xác minh đơn đặt kho " + reservation.getReservationCode(),
            "reservation-verification", variables, "AUTH_EMAIL_SENT", UUID.randomUUID().toString());
    }
}