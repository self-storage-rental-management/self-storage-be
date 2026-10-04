package com.storagehub.service.email;

import com.storagehub.config.EmailProperties;
import com.storagehub.service.AuditLogService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class TransactionalEmailServiceIntegrationTest {

    private static MockSmtpServer smtpServer;
    private static int smtpPort;

    @BeforeAll
    static void startSmtp() throws Exception {
        smtpServer = new MockSmtpServer();
        smtpPort = smtpServer.start();
    }

    @AfterAll
    static void stopSmtp() {
        if (smtpServer != null) {
            smtpServer.stop();
        }
    }

    @Test
    @DisplayName("End-to-End SMTP test: Send all 6 transactional emails, verify receipt, CID logo, and UTF-8")
    void testSendAllSixEmailsViaSmtp() throws Exception {
        // Setup JavaMailSenderImpl
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(smtpPort);
        mailSender.setDefaultEncoding("UTF-8");

        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.mail.javamail.JavaMailSender> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(mailSender);

        // Setup TemplateEngine & Renderer
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
        Mockito.when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});

        TransactionalEmailService emailService = new TransactionalEmailService(
            provider, renderer, properties, auditLogService, environment
        );

        UUID userId = UUID.randomUUID();
        String recipient = "test-user@storagehub.local";

        // 1. Verify email
        emailService.sendVerifyEmailAsync(userId, recipient, "Nguyễn Văn Test", "http://localhost:5173/?verifyEmail=tok1", 15);
        // 2. Password reset
        emailService.sendPasswordResetAsync(userId, recipient, "Nguyễn Văn Test", "http://localhost:5173/?resetPassword=tok2", 15);
        // 3. New login
        emailService.sendNewLoginNoticeAsync(userId, recipient, "Nguyễn Văn Test", "13:30 02/10/2026", "Windows PC", "Chrome", "Không xác định", "127.0.0.1", "http://localhost:5173/security");
        // 4. Account created
        emailService.sendAccountCreatedAsync(userId, recipient, "Nguyễn Văn Test", "02/10/2026 13:30", "Temp#123456", "http://localhost:5173/login");
        // 5. Security alert
        emailService.sendSecurityAlertAsync(userId, recipient, "Nguyễn Văn Test", 10, 15, "13:45 02/10/2026", "13:30 02/10/2026", "127.0.0.1", "Không xác định", "http://localhost:5173/reset", false);
        // 6. Account changed
        emailService.sendAccountChangedAsync(userId, recipient, "Nguyễn Văn Test", "13:30 02/10/2026", "Mật khẩu", "••••••••", "••••••••", false, "Chrome trên Windows PC", "127.0.0.1", "http://localhost:5173/profile");

        // Wait for all 6 messages to be received by mock SMTP
        List<String> received = smtpServer.waitForMessages(6, 10, TimeUnit.SECONDS);
        assertEquals(6, received.size(), "Should have received exactly 6 emails over SMTP");

        for (String rawEmail : received) {
            // Verify Content-Type multipart/related
            assertTrue(rawEmail.contains("multipart/related"), "Must be multipart/related email");
            // Verify CID logo attachment
            assertTrue(rawEmail.contains("storagehubLogo") || rawEmail.contains("storagehub-logo.png"), "Must reference CID storagehubLogo");
            // Verify sender and recipient
            assertTrue(rawEmail.contains("no-reply@storagehub.local"));
            assertTrue(rawEmail.contains(recipient));
        }

        // In Quoted-Printable, '=' is represented as '=3D'
        assertTrue(received.stream().anyMatch(e -> e.contains("verifyEmail") || e.contains("X=C3=A1c minh")));
        assertTrue(received.stream().anyMatch(e -> e.contains("resetPassword") || e.contains("m=E1=BA=ADt kh=E1=BA=A9u")));
        assertTrue(received.stream().anyMatch(e -> e.contains("new-login") || e.contains("Windows PC")));
        assertTrue(received.stream().anyMatch(e -> e.contains("account-created") || e.contains("Temp#123456")));
        assertTrue(received.stream().anyMatch(e -> e.contains("security-alert") || e.contains("gi=E1=BB=9Bi h=E1=BA=A1n")));
        assertTrue(received.stream().anyMatch(e -> e.contains("account-changed") || e.contains("M=E1=BA=ADt kh=E1=BA=A9u")));
    }

    /**
     * Minimal in-memory SMTP server implementing standard RFC 5321 commands.
     */
    static class MockSmtpServer {
        private ServerSocket serverSocket;
        private final List<String> receivedMessages = new CopyOnWriteArrayList<>();
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private volatile boolean running = true;

        int start() throws IOException {
            serverSocket = new ServerSocket(0);
            executor.submit(this::listen);
            return serverSocket.getLocalPort();
        }

        private void listen() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    executor.submit(() -> handleClient(socket));
                } catch (IOException e) {
                    break;
                }
            }
        }

        private void handleClient(Socket socket) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

                writer.write("220 localhost SMTP Ready\r\n");
                writer.flush();

                StringBuilder messageBody = new StringBuilder();
                boolean inData = false;

                String line;
                while ((line = reader.readLine()) != null) {
                    if (inData) {
                        if (line.equals(".")) {
                            inData = false;
                            receivedMessages.add(messageBody.toString());
                            messageBody.setLength(0);
                            writer.write("250 2.0.0 OK message accepted\r\n");
                            writer.flush();
                        } else {
                            messageBody.append(line).append("\r\n");
                        }
                    } else {
                        String upper = line.toUpperCase();
                        if (upper.startsWith("HELO") || upper.startsWith("EHLO")) {
                            writer.write("250-localhost\r\n250 8BITMIME\r\n");
                            writer.flush();
                        } else if (upper.startsWith("MAIL FROM:")) {
                            writer.write("250 2.1.0 Sender OK\r\n");
                            writer.flush();
                        } else if (upper.startsWith("RCPT TO:")) {
                            writer.write("250 2.1.5 Recipient OK\r\n");
                            writer.flush();
                        } else if (upper.startsWith("DATA")) {
                            inData = true;
                            writer.write("354 Start mail input; end with <CRLF>.<CRLF>\r\n");
                            writer.flush();
                        } else if (upper.startsWith("QUIT")) {
                            writer.write("221 2.0.0 Bye\r\n");
                            writer.flush();
                            break;
                        } else if (upper.startsWith("RSET")) {
                            messageBody.setLength(0);
                            writer.write("250 OK\r\n");
                            writer.flush();
                        } else {
                            writer.write("250 OK\r\n");
                            writer.flush();
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }

        List<String> waitForMessages(int expected, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadline) {
                if (receivedMessages.size() >= expected) {
                    return new ArrayList<>(receivedMessages);
                }
                Thread.sleep(50);
            }
            return new ArrayList<>(receivedMessages);
        }

        void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {}
            executor.shutdownNow();
        }
    }
}
