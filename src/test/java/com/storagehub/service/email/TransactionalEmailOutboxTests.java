package com.storagehub.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagehub.config.EmailProperties;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * With {@code app.mail.enabled=false} nothing is sent, which is correct, but the message must still
 * be readable somewhere. The Gmail sender is regularly over its daily quota (550-5.4.5), so a
 * verification code or receipt that only exists in a log line is effectively lost.
 */
class TransactionalEmailOutboxTests {

    @TempDir Path outbox;

    @Test
    void disabledMailStillWritesTheMessageToDiskSoTheCodeCanBeRead() throws Exception {
        TransactionalEmailService service = service();

        // Deliver while mail is off; the returned outcome is a retryable failure but the file must exist.
        service.deliver(UUID.randomUUID(), "thanhthao725218@gmail.com", "StorageHub - verify",
            "verify-email", Map.of("fullName", "Thao", "otp", "482913",
                "verifyUrl", "http://localhost:8443/verify-email?token=abc", "expiryMinutes", 15),
            "EMAIL_VERIFICATION_SENT");

        try (var files = Files.list(outbox)) {
            Path written = files.findFirst().orElseThrow(() -> new AssertionError("no preview written"));
            String body = Files.readString(written);
            assertThat(written.getFileName().toString())
                .contains("verify-email")
                .contains("thanhthao725218@gmail.com");
            assertThat(body)
                .as("the code must be readable without opening the HTML template")
                .contains("482913")
                .contains("token=abc");
        }
    }

    /** Builds the service with mail disabled and the outbox pointed at the temp folder. */
    private TransactionalEmailService service() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> senderProvider = mock(ObjectProvider.class);
        EmailTemplateRenderer renderer = mock(EmailTemplateRenderer.class);
        when(renderer.render(any(), any())).thenReturn("<html><body>rendered</body></html>");

        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"local"});
        when(environment.getProperty(org.mockito.ArgumentMatchers.eq("app.mail.outbox-dir"),
            org.mockito.ArgumentMatchers.eq(".local/outbox"))).thenReturn(outbox.toString());

        TransactionalEmailService service = new TransactionalEmailService(
            senderProvider, renderer, properties,
            mock(com.storagehub.service.AuditLogService.class), environment);
        ReflectionTestUtils.setField(service, "paymentReceiptEmailQueue",
            mock(PaymentReceiptEmailQueue.class));
        return service;
    }

    @SuppressWarnings("unused")
    private static Method unused() { return null; }
}
