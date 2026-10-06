package com.storagehub.api.dev;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.service.email.EmailService;
import com.storagehub.service.email.EmailTemplateRenderer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/email")
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class DevEmailTestController {

    private final EmailService emailService;
    private final EmailTemplateRenderer templateRenderer;

    @Value("${app.mail.test-recipient:test@storagehub.local}")
    private String allowedTestRecipient;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    @PostMapping("/test-all")
    public ResponseEntity<Map<String, Object>> testAll(@RequestParam(defaultValue = "test@storagehub.local") String to) {
        if (!to.equalsIgnoreCase(allowedTestRecipient) && !to.toLowerCase().endsWith("@storagehub.local")) {
            throw ApiExceptions.validation(
                "Dev test emails can only be sent to the configured test recipient (" + allowedTestRecipient + ") or an @storagehub.local address",
                null
            );
        }
        String now = LocalDateTime.now().format(FORMATTER);

        emailService.sendVerifyEmail(to, "Nguyễn Văn Test", "http://localhost:5173/?verifyEmail=sample-token-123", 15);
        emailService.sendPasswordReset(to, "Nguyễn Văn Test", "http://localhost:5173/?resetPassword=sample-token-456", 15);
        emailService.sendNewLoginNotice(to, "Nguyễn Văn Test", now, "Windows PC", "Chrome", "Không xác định", "127.0.0.1", "http://localhost:5173/profile/security");
        emailService.sendAccountCreated(to, "Nguyễn Văn Test", now, "Temp@123456", "http://localhost:5173/login");
        emailService.sendSecurityAlert(to, "Nguyễn Văn Test", 10, 15, now, now, "127.0.0.1", "Không xác định", "http://localhost:5173/?resetPassword=sample-token-789", false);
        emailService.sendAccountChanged(to, "Nguyễn Văn Test", now, "Mật khẩu", "••••••••", "••••••••", false, "Chrome trên Windows", "127.0.0.1", "http://localhost:5173/profile");

        return ResponseEntity.ok(Map.of(
            "message", "Dispatched all 6 transactional test emails",
            "recipient", to,
            "templates", new String[] {
                "verify-email", "reset-password", "new-login", "account-created", "security-alert", "account-changed"
            }
        ));
    }

    @GetMapping(value = "/preview/{template}", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> previewTemplate(@PathVariable String template) {
        String now = LocalDateTime.now().format(FORMATTER);
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", "Nguyễn Văn Test");
        vars.put("email", "test@storagehub.local");

        switch (template) {
            case "verify-email" -> {
                vars.put("verifyUrl", "http://localhost:5173/?verifyEmail=sample-token-123");
                vars.put("expiryMinutes", 15);
            }
            case "reset-password" -> {
                vars.put("resetUrl", "http://localhost:5173/?resetPassword=sample-token-456");
                vars.put("expiryMinutes", 15);
            }
            case "new-login" -> {
                vars.put("loginTime", now);
                vars.put("device", "Windows PC");
                vars.put("browser", "Chrome");
                vars.put("location", "Không xác định");
                vars.put("ipAddress", "127.0.0.1");
                vars.put("securityUrl", "http://localhost:5173/profile/security");
            }
            case "account-created" -> {
                vars.put("createdAt", now);
                vars.put("tempPassword", "Temp@123456");
                vars.put("loginUrl", "http://localhost:5173/login");
            }
            case "security-alert" -> {
                vars.put("failedCount", 10);
                vars.put("windowMinutes", 15);
                vars.put("unlockTime", now);
                vars.put("lastAttemptTime", now);
                vars.put("ipAddress", "127.0.0.1");
                vars.put("location", "Không xác định");
                vars.put("resetUrl", "http://localhost:5173/?resetPassword=sample-token-789");
                vars.put("isLocked", false);
            }
            case "account-changed" -> {
                vars.put("changeTime", now);
                vars.put("fieldName", "Mật khẩu");
                vars.put("oldValue", "••••••••");
                vars.put("newValue", "••••••••");
                vars.put("performedByAdmin", false);
                vars.put("device", "Chrome trên Windows");
                vars.put("ipAddress", "127.0.0.1");
                vars.put("accountUrl", "http://localhost:5173/profile");
            }
            default -> {
                return ResponseEntity.badRequest().body("Unknown template: " + template);
            }
        }

        String html = templateRenderer.render(template, vars);
        return ResponseEntity.ok(html);
    }
}
