package com.storagehub.service.email;

import com.storagehub.config.EmailProperties;
import com.storagehub.service.AuditLogService;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalEmailService {

    private static final String LOGO_PATH = "static/email/storagehub-logo.png";
    private static final String LOGO_CID = "storagehubLogo";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final EmailTemplateRenderer templateRenderer;
    private final EmailProperties emailProperties;
    private final AuditLogService auditLogService;
    private final Environment environment;

    @Async
    public void sendVerifyEmailAsync(UUID userId, String toEmail, String fullName, String verifyUrl, int expiryMinutes) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("email", toEmail);
        vars.put("verifyUrl", verifyUrl);
        vars.put("expiryMinutes", expiryMinutes);

        sendInternal(userId, toEmail, "StorageHub - Xác minh địa chỉ email của bạn", "verify-email", vars, "EMAIL_VERIFICATION_SENT");
    }

    @Async
    public void sendPasswordResetAsync(UUID userId, String toEmail, String fullName, String resetUrl, int expiryMinutes) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("email", toEmail);
        vars.put("resetUrl", resetUrl);
        vars.put("expiryMinutes", expiryMinutes);

        sendInternal(userId, toEmail, "StorageHub - Đặt lại mật khẩu", "reset-password", vars, "PASSWORD_RESET_EMAIL_SENT");
    }

    @Async
    public void sendNewLoginNoticeAsync(
        UUID userId,
        String toEmail,
        String fullName,
        String loginTime,
        String device,
        String browser,
        String location,
        String ipAddress,
        String securityUrl
    ) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("loginTime", loginTime);
        vars.put("device", device != null ? device : "Không xác định");
        vars.put("browser", browser != null ? browser : "Không xác định");
        vars.put("location", location != null ? location : "Không xác định");
        vars.put("ipAddress", ipAddress != null ? ipAddress : "Không xác định");
        vars.put("securityUrl", securityUrl != null ? securityUrl : emailProperties.getSecurityUrl());

        sendInternal(userId, toEmail, "StorageHub - Đăng nhập mới vào tài khoản StorageHub", "new-login", vars, "NEW_LOGIN_EMAIL_SENT");
    }

    @Async
    public void sendAccountCreatedAsync(UUID userId, String toEmail, String fullName, String createdAt, String tempPassword, String loginUrl) {
        sendAccountCreatedAsync(userId, toEmail, fullName, createdAt, tempPassword, loginUrl, false);
    }

    @Async
    public void sendAccountCreatedAsync(
        UUID userId,
        String toEmail,
        String fullName,
        String createdAt,
        String tempPassword,
        String loginUrl,
        boolean isReset
    ) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("email", toEmail);
        vars.put("createdAt", createdAt);
        vars.put("resetTime", createdAt);
        vars.put("tempPassword", tempPassword);
        vars.put("loginUrl", loginUrl != null ? loginUrl : emailProperties.getLoginUrl());
        vars.put("isReset", isReset);
        vars.put("resetMode", isReset);

        String subject = isReset
            ? "StorageHub - Mật khẩu StorageHub của bạn đã được đặt lại"
            : "StorageHub - Tài khoản StorageHub của bạn đã được tạo";
        String auditAction = isReset ? "ACCOUNT_PASSWORD_RESET_EMAIL_SENT" : "ACCOUNT_CREATED_EMAIL_SENT";

        sendInternal(userId, toEmail, subject, "account-created", vars, auditAction);
    }

    @Async
    public void sendSecurityAlertAsync(
        UUID userId,
        String toEmail,
        String fullName,
        int failedCount,
        int windowMinutes,
        String unlockTime,
        String lastAttemptTime,
        String ipAddress,
        String location,
        String resetUrl,
        boolean isLocked
    ) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("failedCount", failedCount);
        vars.put("windowMinutes", windowMinutes);
        vars.put("unlockTime", unlockTime);
        vars.put("lastAttemptTime", lastAttemptTime);
        vars.put("ipAddress", ipAddress != null ? ipAddress : "Không xác định");
        vars.put("location", location != null ? location : "Không xác định");
        vars.put("resetUrl", resetUrl);
        vars.put("isLocked", isLocked);

        sendInternal(userId, toEmail, "StorageHub - Cảnh báo bảo mật: hoạt động bất thường", "security-alert", vars, "SECURITY_ALERT_EMAIL_SENT");
    }

    @Async
    public void sendAccountChangedAsync(
        UUID userId,
        String toEmail,
        String fullName,
        String changeTime,
        String fieldName,
        String oldValue,
        String newValue,
        boolean performedByAdmin,
        String device,
        String ipAddress,
        String accountUrl
    ) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("changeTime", changeTime);
        vars.put("fieldName", fieldName);
        vars.put("oldValue", oldValue);
        vars.put("newValue", newValue);
        vars.put("performedByAdmin", performedByAdmin);
        vars.put("device", device);
        vars.put("ipAddress", ipAddress);
        vars.put("accountUrl", accountUrl != null ? accountUrl : emailProperties.getAccountUrl());

        sendInternal(userId, toEmail, "StorageHub - Thông tin tài khoản của bạn đã thay đổi", "account-changed", vars, "ACCOUNT_CHANGED_EMAIL_SENT");
    }

    private void sendInternal(
        UUID userId,
        String recipient,
        String subject,
        String templateName,
        Map<String, Object> variables,
        String auditAction
    ) {
        boolean isDev = isDevProfile();

        if (!emailProperties.isEnabled()) {
            log.info("Email delivery skipped (app.mail.enabled=false) for template={} recipient={}", templateName, recipient);
            if (isDev) {
                logDevPreview(recipient, templateName, variables);
            }
            recordAuditSafely(userId, auditAction, recipient, subject, "SKIPPED_DISABLED");
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("JavaMailSender bean is not configured; skipping email template={} to recipient={}", templateName, recipient);
            if (isDev) {
                logDevPreview(recipient, templateName, variables);
            }
            recordAuditSafely(userId, auditAction, recipient, subject, "SKIPPED_NO_SENDER");
            return;
        }

        try {
            Resource logoResource = new ClassPathResource(LOGO_PATH);
            boolean hasPublicLogo = emailProperties.getLogoUrl() != null
                && !emailProperties.getLogoUrl().isBlank();
            boolean hasInlineLogo = !hasPublicLogo && logoResource.exists();
            if (hasInlineLogo) {
                variables.put("logoCid", LOGO_CID);
            }

            String html = templateRenderer.render(templateName, variables);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                mimeMessage,
                MimeMessageHelper.MULTIPART_MODE_RELATED,
                StandardCharsets.UTF_8.name()
            );

            helper.setFrom(emailProperties.getFrom());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(html, true);

            if (hasInlineLogo) {
                helper.addInline(LOGO_CID, logoResource, "image/png");
            }

            mailSender.send(mimeMessage);
            log.info("Email sent successfully: template={} to={}", templateName, recipient);
            recordAuditSafely(userId, auditAction, recipient, subject, "SENT");
        } catch (Exception exception) {
            log.error("Failed to send email template={} to recipient={}: {}", templateName, recipient, exception.getMessage());
            recordAuditSafely(userId, auditAction, recipient, subject, "FAILED: " + exception.getClass().getSimpleName());
        }
    }

    private void logDevPreview(String recipient, String templateName, Map<String, Object> variables) {
        Object link = variables.get("verifyUrl");
        if (link == null) link = variables.get("resetUrl");
        if (link == null) link = variables.get("loginUrl");
        if (link == null) link = variables.get("securityUrl");
        if (link == null) link = variables.get("accountUrl");

        if (link != null) {
            log.info("[DEV EMAIL PREVIEW] To: {}, Template: {}, Action Link: {}", recipient, templateName, link);
        } else {
            log.info("[DEV EMAIL PREVIEW] To: {}, Template: {}", recipient, templateName);
        }
    }

    private void recordAuditSafely(UUID userId, String action, String recipient, String subject, String deliveryStatus) {
        if (auditLogService == null) {
            return;
        }
        try {
            UUID targetId = userId != null ? userId : UUID.fromString("00000000-0000-0000-0000-000000000000");
            Map<String, Object> metadata = Map.of(
                "recipient", recipient,
                "subject", subject,
                "status", deliveryStatus
            );
            auditLogService.recordMutation(action, "User", targetId, null, null, metadata);
        } catch (Exception ex) {
            log.warn("Could not record email audit log: {}", ex.getMessage());
        }
    }

    private boolean isDevProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equalsIgnoreCase(profile) || "development".equalsIgnoreCase(profile) || "local".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
