package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthEmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.enabled:false}")
    private boolean enabled;

    @Value("${app.mail.from:no-reply@storagehub.local}")
    private String from;

    @Value("${app.auth.verification-url:http://localhost:5173/?verifyEmail=}")
    private String verificationUrl;

    @Value("${app.auth.password-reset-url:http://localhost:5173/?resetPassword=}")
    private String passwordResetUrl;

    @Value("${app.auth.expose-development-code:false}")
    private boolean exposeDevelopmentCode;

    public void sendVerification(User user, String otp, String token) {
        send(
            user.getEmail(),
            "StorageHub - Xác minh email",
            "Mã xác minh StorageHub của bạn là: " + otp + "\n\n"
                + "Liên kết xác minh: " + verificationUrl + token + "\n\n"
                + "Mã hết hạn sau 15 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này."
        );
    }

    public void sendPasswordReset(User user, String otp, String token) {
        send(
            user.getEmail(),
            "StorageHub - Đặt lại mật khẩu",
            "Mã đặt lại mật khẩu StorageHub của bạn là: " + otp + "\n\n"
                + "Liên kết đặt lại mật khẩu: " + passwordResetUrl + token + "\n\n"
                + "Mã hết hạn sau 15 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này."
        );
    }

    public void sendReservationVerification(Reservation reservation, String otp) {
        send(
            reservation.getCustomer().getEmail(),
            "StorageHub - Xác minh đơn đặt kho " + reservation.getReservationCode(),
            "Mã xác minh đơn đặt kho của bạn là: " + otp + "\n\n"
                + "Mã đơn: " + reservation.getReservationCode() + "\n"
                + "Mã hết hạn sau 10 phút. Nếu bạn không tạo đơn này, hãy liên hệ StorageHub."
        );
    }

    private void send(String recipient, String subject, String text) {
        if (!enabled) {
            if (!exposeDevelopmentCode) {
                throw ApiExceptions.conflict("The email delivery service is not configured");
            }
            return;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw ApiExceptions.conflict("The email delivery service is not configured");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw ApiExceptions.conflict("The email delivery service is unavailable");
        }
    }
}
