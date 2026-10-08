package com.storagehub.service;

import com.storagehub.config.EmailProperties;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.User;
import com.storagehub.service.email.EmailTemplateRenderer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthEmailService {

    private static final String LOGO_PATH = "static/email/storagehub-logo.png";
    private static final String LOGO_CID = "storagehubLogo";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final EmailTemplateRenderer templateRenderer;
    private final EmailProperties emailProperties;

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
        Map<String, Object> variables = new HashMap<>();
        variables.put("fullName", user.getFullName());
        variables.put("email", user.getEmail());
        variables.put("verifyUrl", verificationUrl + token);
        variables.put("expiryMinutes", 15);
        variables.put("otp", otp);

        sendTemplate(
            user.getEmail(),
            "StorageHub - Xác minh email đăng ký",
            "verify-email",
            variables,
            "Xin chào " + user.getFullName() + ",\n\n"
                + "Cảm ơn bạn đã đăng ký tài khoản StorageHub.\n\n"
                + "Xác minh nhanh: " + verificationUrl + token + "\n\n"
                + "Mã xác minh thủ công: " + otp + "\n\n"
                + "Liên kết và mã hết hạn sau 15 phút. Nếu bạn không yêu cầu đăng ký, hãy bỏ qua email này."
        );
    }

    public void sendPasswordReset(User user, String otp, String token) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("fullName", user.getFullName());
        variables.put("email", user.getEmail());
        variables.put("resetUrl", passwordResetUrl + token);
        variables.put("expiryMinutes", 15);
        variables.put("otp", otp);

        sendTemplate(
            user.getEmail(),
            "StorageHub - Đặt lại mật khẩu",
            "reset-password",
            variables,
            "Xin chào " + user.getFullName() + ",\n\n"
                + "Bạn vừa yêu cầu đặt lại mật khẩu StorageHub.\n\n"
                + "Đặt lại nhanh: " + passwordResetUrl + token + "\n\n"
                + "Mã đặt lại thủ công: " + otp + "\n\n"
                + "Liên kết và mã hết hạn sau 15 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này."
        );
    }

    public void sendReservationVerification(Reservation reservation, String otp) {
        String reservationCode = reservation.getReservationCode();
        Map<String, Object> variables = new HashMap<>();
        variables.put("fullName", reservation.getCustomer().getFullName());
        variables.put("reservationCode", reservationCode);
        variables.put("otp", otp);
        variables.put("expiryMinutes", 10);

        sendTemplate(
            reservation.getCustomer().getEmail(),
            "StorageHub - Xác minh đơn đặt kho " + reservationCode,
            "reservation-verification",
            variables,
            "Mã xác minh đơn đặt kho của bạn là: " + otp + "\n\n"
                + "Mã đơn: " + reservationCode + "\n"
                + "Mã hết hạn sau 10 phút. Nếu bạn không tạo đơn này, hãy liên hệ StorageHub."
        );
    }

    private void sendTemplate(
        String recipient,
        String subject,
        String templateName,
        Map<String, Object> variables,
        String text
    ) {
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

        Resource logoResource = new ClassPathResource(LOGO_PATH);
        boolean hasPublicLogo = emailProperties.getLogoUrl() != null
            && !emailProperties.getLogoUrl().isBlank();
        boolean hasInlineLogo = !hasPublicLogo && logoResource.exists();
        if (hasInlineLogo) {
            variables.put("logoCid", LOGO_CID);
        }

        try {
            String html = templateRenderer.render(templateName, variables);
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                mimeMessage,
                MimeMessageHelper.MULTIPART_MODE_RELATED,
                StandardCharsets.UTF_8.name()
            );
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(text, html);

            if (hasInlineLogo) {
                helper.addInline(LOGO_CID, logoResource, "image/png");
            }

            mailSender.send(mimeMessage);
        } catch (MessagingException | MailException exception) {
            throw ApiExceptions.conflict("The email delivery service is unavailable");
        }
    }
}
