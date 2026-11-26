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
    @org.springframework.beans.factory.annotation.Autowired
    private PaymentReceiptEmailQueue paymentReceiptEmailQueue;

    public void sendVerifyEmailAsync(UUID userId, String toEmail, String fullName, String verifyUrl, int expiryMinutes) {
        sendVerifyEmailAsync(userId, toEmail, fullName, verifyUrl, expiryMinutes, null);
    }

    public void sendVerifyEmailAsync(UUID userId, String toEmail, String fullName, String verifyUrl, int expiryMinutes, String otp) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("email", toEmail);
        vars.put("verifyUrl", verifyUrl);
        vars.put("otp", otp);
        vars.put("expiryMinutes", expiryMinutes);
        vars.put("_expiresAt", java.time.Instant.now().plusSeconds(expiryMinutes * 60L).toString());

        sendInternal(userId, toEmail, "StorageHub - Xác minh địa chỉ email của bạn", "verify-email", vars, "EMAIL_VERIFICATION_SENT");
    }

    public void sendPasswordResetAsync(UUID userId, String toEmail, String fullName, String resetUrl, int expiryMinutes) {
        sendPasswordResetAsync(userId, toEmail, fullName, resetUrl, expiryMinutes, null);
    }

    public void sendPasswordResetAsync(
        UUID userId,
        String toEmail,
        String fullName,
        String resetUrl,
        int expiryMinutes,
        String otp
    ) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("email", toEmail);
        vars.put("resetUrl", resetUrl);
        vars.put("expiryMinutes", expiryMinutes);
        vars.put("otp", otp);

        sendInternal(userId, toEmail, "StorageHub - Đặt lại mật khẩu", "reset-password", vars, "PASSWORD_RESET_EMAIL_SENT");
    }

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

    public void sendAccountCreatedAsync(UUID userId, String toEmail, String fullName, String createdAt, String tempPassword, String loginUrl) {
        sendAccountCreatedAsync(userId, toEmail, fullName, createdAt, tempPassword, loginUrl, false);
    }

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
        // Passwords are never delivered by email. Use the one-time reset flow.
        vars.put("tempPassword", null);
        vars.put("loginUrl", loginUrl != null ? loginUrl : emailProperties.getLoginUrl());
        vars.put("isReset", isReset);
        vars.put("resetMode", isReset);

        String subject = isReset
            ? "StorageHub - Mật khẩu StorageHub của bạn đã được đặt lại"
            : "StorageHub - Tài khoản StorageHub của bạn đã được tạo";
        String auditAction = isReset ? "ACCOUNT_PASSWORD_RESET_EMAIL_SENT" : "ACCOUNT_CREATED_EMAIL_SENT";

        sendInternal(userId, toEmail, subject, "account-created", vars, auditAction);
    }

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
        boolean phoneChange = fieldName != null && fieldName.toLowerCase(java.util.Locale.ROOT).contains("điện thoại");
        vars.put("oldValue", phoneChange ? maskContact(oldValue) : oldValue);
        vars.put("newValue", phoneChange ? maskContact(newValue) : newValue);
        vars.put("performedByAdmin", performedByAdmin);
        vars.put("device", device);
        vars.put("ipAddress", ipAddress);
        vars.put("accountUrl", accountUrl != null ? accountUrl : emailProperties.getAccountUrl());

        sendInternal(userId, toEmail, "StorageHub - Thông tin tài khoản của bạn đã thay đổi", "account-changed", vars, "ACCOUNT_CHANGED_EMAIL_SENT");
    }

    public void sendPaymentBillAsync(
        UUID userId,
        String toEmail,
        String fullName,
        UUID paymentId,
        String transactionNo,
        String amountFormatted,
        String paidAtFormatted,
        String unitInfo,
        String facilityName,
        String facilityAddress,
        String periodOrAppointment,
        String paymentPurpose
    ) {
        sendPaymentBillAsync(userId, toEmail, fullName, paymentId, transactionNo, amountFormatted,
            paidAtFormatted, unitInfo, facilityName, facilityAddress, periodOrAppointment, paymentPurpose, null);
    }

    public void sendPaymentBillAsync(
        UUID userId,
        String toEmail,
        String fullName,
        UUID paymentId,
        String transactionNo,
        String amountFormatted,
        String paidAtFormatted,
        String unitInfo,
        String facilityName,
        String facilityAddress,
        String periodOrAppointment,
        String paymentPurpose,
        String note
    ) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", fullName);
        vars.put("email", toEmail);
        vars.put("paymentId", paymentId.toString());
        vars.put("transactionNo", transactionNo != null ? transactionNo : "N/A");
        vars.put("amount", amountFormatted);
        vars.put("paidAt", paidAtFormatted);
        vars.put("unitInfo", unitInfo != null ? unitInfo : "N/A");
        vars.put("unitCode", unitInfo != null ? unitInfo : "N/A");
        vars.put("facilityName", facilityName != null ? facilityName : "StorageHub");
        vars.put("facilityAddress", facilityAddress != null ? facilityAddress : "");
        vars.put("periodOrAppointment", periodOrAppointment != null ? periodOrAppointment : "N/A");
        vars.put("newEndDate", periodOrAppointment != null ? periodOrAppointment : "N/A");
        vars.put("paymentPurpose", paymentPurpose != null ? paymentPurpose : "Thanh toán dịch vụ");
        vars.put("note", note);

        paymentReceiptEmailQueue.enqueue(userId, toEmail, vars);
    }

    public void queueTemplate(UUID userId, String recipient, String subject, String template,
                              Map<String, Object> variables, String auditAction, String eventKey) {
        paymentReceiptEmailQueue.enqueue(userId, recipient, template, subject, auditAction, eventKey, variables);
    }

    /**
     * Event key for account emails.
     *
     * A random key defeated the queue's duplicate protection entirely (`ON DUPLICATE KEY` never
     * matched), so a retried action enqueued a brand new email each time and the sender's daily
     * quota burned out. Templates that describe a wanted state (verify, reset, account created or
     * changed) are keyed by their purpose and owner so repeating the action collapses into one
     * queued message. Per-event notices keep a unique key because each login or alert is a
     * separate event.
     */
    private static final java.util.Set<String> STATEFUL_TEMPLATES =
        java.util.Set.of("verify-email", "reset-password", "account-created", "account-changed");

    private static String eventKeyFor(String template, UUID userId, String recipient) {
        if (!STATEFUL_TEMPLATES.contains(template)) return UUID.randomUUID().toString();
        return template + ":" + (userId == null ? recipient : userId);
    }

    private void sendInternal(UUID userId, String recipient, String subject, String template,
                                 Map<String, Object> variables, String auditAction) {
        queueTemplate(userId, recipient, subject, template, variables, auditAction,
            eventKeyFor(template, userId, recipient));
    }

    public String deliveryUnavailableReason() {
        if (!emailProperties.isEnabled()) return "MAIL_DISABLED";
        return mailSenderProvider.getIfAvailable() == null ? "SMTP_NOT_CONFIGURED" : null;
    }

    /**
     * Outcome of one delivery attempt. {@link #PERMANENT_FAILURE} means the same message can
     * never be accepted (bad mailbox, or the sender is over its daily/relay quota), so retrying
     * only spends more of the account's allowance — the queue must stop instead.
     */
    public enum DeliveryOutcome { SENT, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    public boolean deliverEmail(
        UUID userId,
        String recipient,
        String subject,
        String templateName,
        Map<String, Object> variables,
        String auditAction
    ) {
        return deliver(userId, recipient, subject, templateName, variables, auditAction) == DeliveryOutcome.SENT;
    }

    public DeliveryOutcome deliver(
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
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("JavaMailSender bean is not configured; skipping email template={} to recipient={}", templateName, recipient);
            if (isDev) {
                logDevPreview(recipient, templateName, variables);
            }
            recordAuditSafely(userId, auditAction, recipient, subject, "SKIPPED_NO_SENDER");
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }

        try {
            Resource logoResource = new ClassPathResource(LOGO_PATH);
            boolean hasPublicLogo = emailProperties.getLogoUrl() != null
                && !emailProperties.getLogoUrl().isBlank();
            boolean hasInlineLogo = !hasPublicLogo && logoResource.exists();
            if (hasInlineLogo) {
                variables.put("logoCid", LOGO_CID);
            }
            if ("payment-bill".equals(templateName)) {
                variables.putIfAbsent("detailsUrl", emailProperties.getFrontendBaseUrl().replaceAll("/+$", "") + "/login?emailSection=payments");
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
            return DeliveryOutcome.SENT;
        } catch (Exception exception) {
            String described = describeMailException(exception);
            boolean permanent = permanentFailure(described);
            log.error("Failed to send email template={} to recipient={}: {}", templateName, recipient, described);
            recordAuditSafely(userId, auditAction, recipient, subject,
                (permanent ? "PERMANENT_FAILURE: " : "FAILED: ") + exception.getClass().getSimpleName());
            if (permanent) {
                // A rejected or quota-exhausted message cannot succeed on retry. Retrying only
                // burns more of the sender's daily allowance, which is what starved every other
                // email for days. Drop it and let the operator see why.
                log.warn("Dropping non-retryable email template={} to recipient={}: {}", templateName, recipient, described);
                return DeliveryOutcome.PERMANENT_FAILURE;
            }
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }
    }

    /**
     * True when re-sending the same message cannot succeed. Covers a sender over its daily or
     * relay quota, an invalid mailbox, and a refused recipient — the cases that made the retry
     * loop exhaust the SMTP account.
     */
    static boolean permanentFailure(String described) {
        if (described == null) return false;
        String text = described.toLowerCase(java.util.Locale.ROOT);
        return text.contains("sending limit exceeded")
            || text.contains("quota")
            || text.contains("5.4.5")
            || text.contains("5.7.1")
            || text.contains("user unknown")
            || text.contains("does not exist")
            || text.contains("mailbox unavailable")
            || text.contains("recipient address rejected");
    }

    private String describeMailException(Exception exception) {
        StringBuilder details = new StringBuilder();
        Throwable current = exception;
        int depth = 0;
        while (current != null && depth < 6) {
            if (!details.isEmpty()) details.append(" -> ");
            details.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            if (current instanceof jakarta.mail.MessagingException messaging && messaging.getNextException() != null
                && messaging.getNextException() != current.getCause()) {
                Exception next = messaging.getNextException();
                details.append(" [next: ").append(next.getClass().getSimpleName()).append(": ").append(next.getMessage()).append("]");
            }
            current = current.getCause();
            depth++;
        }
        return details.toString();
    }

    private String maskContact(String value) {
        if (value == null || value.length() < 4) return "Đã cập nhật";
        return "••••" + value.substring(value.length() - 3);
    }

    /**
     * When mail sending is off, still write the rendered message to disk so it can be read
     * without an SMTP account. The sender's Gmail quota is easily exhausted (550-5.4.5 daily
     * sending limit), which used to leave no way to see a verification code or a receipt.
     * Files land in {@code app.mail.outbox-dir} (default {@code .local/outbox}) and are ignored by git.
     */
    private void logDevPreview(String recipient, String templateName, Map<String, Object> variables) {
        log.info("[DEV EMAIL PREVIEW] Template: {}. Open the local development screen for verification codes.", templateName);
        writeOutbox(recipient, templateName, variables);
    }

    /**
     * Renders a queued message into the local outbox. Used when mail is deliberately disabled so
     * the operator can still read verification codes and receipts without an SMTP account.
     */
    public void savePreview(String recipient, String subject, String templateName, Map<String, Object> variables) {
        writeOutbox(recipient, templateName == null ? "email" : templateName, variables, subject);
    }

    private void writeOutbox(String recipient, String templateName, Map<String, Object> variables) {
        writeOutbox(recipient, templateName, variables, null);
    }

    private void writeOutbox(String recipient, String templateName, Map<String, Object> variables, String subject) {
        try {
            String dir = environment.getProperty("app.mail.outbox-dir", ".local/outbox");
            java.nio.file.Path folder = java.nio.file.Paths.get(dir).toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(folder);
            String stamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(java.time.Instant.now());
            String safeRecipient = recipient == null ? "unknown"
                : recipient.replaceAll("[^A-Za-z0-9.@_-]", "_");
            java.nio.file.Path file = folder.resolve(stamp + "-" + templateName + "-" + safeRecipient + ".html");
            String html = templateRenderer.render(templateName, variables);
            // Record the codes the templates hide behind their buttons so they are easy to copy.
            StringBuilder details = new StringBuilder();
            for (String key : new String[] {"otp", "verifyUrl", "resetUrl"}) {
                Object value = variables.get(key);
                if (value != null && !String.valueOf(value).isBlank())
                    details.append("<p><b>").append(key).append(":</b> ")
                        .append(java.util.regex.Pattern.compile("<[^>]*>").matcher(String.valueOf(value)).replaceAll(""))
                        .append("</p>");
            }
            java.nio.file.Files.writeString(file,
                "<!doctype html><meta charset=\"utf-8\"><title>" + templateName + "</title>"
                    + "<p><b>To:</b> " + recipient + "<br><b>Subject:</b> "
                    + (subject == null ? "" : subject) + "<br><b>Template:</b> " + templateName + "</p>"
                    + details + "<hr>" + html,
                StandardCharsets.UTF_8);
            log.info("[DEV EMAIL SAVED] {} -> {}", templateName, file);
        } catch (Exception failure) {
            log.warn("Could not write the local email preview for template={}: {}", templateName, failure.toString());
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
