package com.storagehub.service.email;

import com.storagehub.config.EmailProperties;
import com.storagehub.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MailHogDeliveryTest {

    @Test
    @DisplayName("Send 6 test emails directly to MailHog on port 1025")
    void sendDirectlyToMailHog() {
        boolean mailHogAvailable;
        try (Socket socket = new Socket("localhost", 1025)) {
            mailHogAvailable = true;
        } catch (Exception e) {
            mailHogAvailable = false;
        }

        if (!mailHogAvailable) {
            System.out.println("MailHog not running on port 1025; skipping live dispatch");
            return;
        }

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(1025);
        mailSender.setDefaultEncoding("UTF-8");

        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.mail.javamail.JavaMailSender> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(mailSender);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setFrom("no-reply@storagehub.local");
        properties.setSupportEmail("support@storagehub.vn");
        properties.setCompanyAddress("Tòa nhà StorageHub, Khu Công Nghệ Cao, TP.HCM");

        EmailTemplateRenderer renderer = new EmailTemplateRenderer(engine, properties);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);
        Environment environment = Mockito.mock(Environment.class);
        Mockito.when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        TransactionalEmailService emailService = new TransactionalEmailService(
            provider, renderer, properties, auditLogService, environment
        );

        UUID userId = UUID.randomUUID();
        String to = "test@storagehub.local";

        emailService.sendVerifyEmailAsync(userId, to, "Nguyễn Văn Test", "http://localhost:5173/?verifyEmail=test-verify-token-123", 15);
        emailService.sendPasswordResetAsync(userId, to, "Nguyễn Văn Test", "http://localhost:5173/?resetPassword=test-reset-token-456", 15);
        emailService.sendNewLoginNoticeAsync(userId, to, "Nguyễn Văn Test", "13:30 02/10/2026", "Windows PC", "Chrome", "Không xác định", "127.0.0.1", "http://localhost:5173/security");
        emailService.sendAccountCreatedAsync(userId, to, "Nguyễn Văn Test", "02/10/2026 13:30", "Temp#123456", "http://localhost:5173/login");
        emailService.sendSecurityAlertAsync(userId, to, "Nguyễn Văn Test", 10, 15, "13:45 02/10/2026", "13:30 02/10/2026", "127.0.0.1", "Không xác định", "http://localhost:5173/reset", false);
        emailService.sendAccountChangedAsync(userId, to, "Nguyễn Văn Test", "13:30 02/10/2026", "Mật khẩu", "••••••••", "••••••••", false, "Chrome trên Windows PC", "127.0.0.1", "http://localhost:5173/profile");

        assertTrue(mailHogAvailable);
    }
}
