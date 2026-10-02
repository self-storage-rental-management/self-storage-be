package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.User;
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
            "StorageHub - Xác minh email đăng ký",
            "Xin chào " + user.getFullName() + ",\n\n"
                + "Cảm ơn bạn đã đăng ký tài khoản StorageHub.\n\n"
                + "CÁCH 1 - Xác minh nhanh:\n"
                + "Bấm vào liên kết bên dưới để xác minh tự động (không cần nhập token):\n"
                + verificationUrl + token + "\n\n"
                + "CÁCH 2 - Nhập mã thủ công:\n"
                + "Nhập mã 6 chữ số này tại màn hình xác minh email: " + otp + "\n\n"
                + "Liên kết và mã hết hạn sau 15 phút. Nếu bạn không yêu cầu đăng ký, hãy bỏ qua email này."
        );
    }

    public void sendPasswordReset(User user, String otp, String token) {
        send(
            user.getEmail(),
            "StorageHub - Đặt lại mật khẩu",
            "Xin chào " + user.getFullName() + ",\n\n"
                + "Bạn vừa yêu cầu đặt lại mật khẩu StorageHub.\n\n"
                + "CÁCH 1 - Đặt lại nhanh:\n"
                + "Bấm vào liên kết bên dưới để tạo mật khẩu mới:\n"
                + passwordResetUrl + token + "\n\n"
                + "CÁCH 2 - Nhập mã thủ công:\n"
                + "Nhập mã 6 chữ số này tại màn hình khôi phục tài khoản: " + otp + "\n\n"
                + "Liên kết và mã hết hạn sau 15 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này."
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
