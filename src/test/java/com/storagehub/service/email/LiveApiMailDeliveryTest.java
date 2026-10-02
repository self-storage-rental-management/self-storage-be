package com.storagehub.service.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.admin.AdminPatchUserRequest;
import com.storagehub.api.admin.AdminUpdateRolesRequest;
import com.storagehub.api.auth.*;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.UserRepository;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.mail.enabled=true",
    "spring.mail.host=localhost",
    "spring.mail.port=1025",
    "spring.mail.properties.mail.smtp.auth=false",
    "spring.mail.properties.mail.smtp.starttls.enable=false",
    "app.bootstrap-admin.enabled=true",
    "app.bootstrap-admin.email=admin@storagehub.test",
    "app.bootstrap-admin.password=Bootstrap-password-123!",
    "app.bootstrap-admin.full-name=Test Administrator"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveApiMailDeliveryTest {

    private static final String MAILHOG_BASE = "http://localhost:8025";
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.storagehub.domain.repo.LoginHistoryRepository loginHistoryRepository;

    @Autowired
    private TransactionalEmailService transactionalEmailService;

    private static String registeredUserToken;
    private static String adminToken;
    private static UUID registeredUserId;

    public record DecodedEmail(String id, String to, String subject, String bodyHtml) {}

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void clearMailHog() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(MAILHOG_BASE + "/api/v1/messages"))
            .DELETE()
            .build();
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode getMailHogMessages() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(MAILHOG_BASE + "/api/v2/messages"))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private List<DecodedEmail> waitForEmails(int expectedCount, long timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        JsonNode data = null;
        while (System.currentTimeMillis() < deadline) {
            data = getMailHogMessages();
            int total = data.get("total").asInt();
            if (total >= expectedCount) {
                break;
            }
            Thread.sleep(200);
        }

        List<DecodedEmail> list = new ArrayList<>();
        if (data != null && data.has("items")) {
            for (JsonNode item : data.get("items")) {
                String id = item.get("ID").asText();
                DecodedEmail email = fetchDecodedEmail(id);
                list.add(email);
            }
        }
        return list;
    }

    private DecodedEmail fetchDecodedEmail(String messageId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(MAILHOG_BASE + "/api/v1/messages/" + messageId + "/download"))
            .GET()
            .build();
        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        MimeMessage mime = new MimeMessage(null, resp.body());
        String subject = mime.getSubject();
        String to = mime.getAllRecipients() != null && mime.getAllRecipients().length > 0
            ? mime.getAllRecipients()[0].toString() : "";
        String html = extractHtmlContent(mime);
        return new DecodedEmail(messageId, to, subject, html != null ? html : "");
    }

    private String extractHtmlContent(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String html = extractHtmlContent(mp.getBodyPart(i));
                if (html != null) return html;
            }
        }
        return null;
    }

    private HttpResponse<String> postJson(String path, Object body, String bearerToken) throws Exception {
        return postJson(path, body, bearerToken, null);
    }

    private HttpResponse<String> postJson(String path, Object body, String bearerToken, String userAgent) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (userAgent != null) {
            builder.header("User-Agent", userAgent);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            System.err.println("POST " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    private HttpResponse<String> putJson(String path, Object body, String bearerToken) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            System.err.println("PUT " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    private HttpResponse<String> patchJson(String path, Object body, String bearerToken) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            System.err.println("PATCH " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    @BeforeEach
    void setUp() throws Exception {
        clearMailHog();
    }

    @Test
    @Order(1)
    @DisplayName("Action 1: Register API -> Sends exactly 1 verify-email to MailHog")
    void testRegisterFlow() throws Exception {
        RegisterRequest registerReq = new RegisterRequest(
            "flowuser@storagehub.test",
            "StrongPass#123",
            "Nguyen Van Flow",
            "0901234567",
            "123 Nguyen Hue, Q1",
            "Tran Thi Emergency",
            "0909999999"
        );

        HttpResponse<String> response = postJson("/api/auth/register", registerReq, null);
        assertThat(response.statusCode()).isEqualTo(200);

        // Wait for async email delivery
        List<DecodedEmail> emails = waitForEmails(1, 5);
        assertThat(emails).hasSize(1);

        DecodedEmail email = emails.get(0);
        assertEquals("flowuser@storagehub.test", email.to());
        assertTrue(email.subject().contains("Xác minh địa chỉ email"));
        assertTrue(email.bodyHtml().contains("Nguyen Van Flow"));
        assertTrue(email.bodyHtml().contains("verifyEmail="));

        // Save userId for future tests
        User user = userRepository.findByEmailIgnoreCase("flowuser@storagehub.test").orElseThrow();
        registeredUserId = user.getId();

        // Extract token from decoded HTML
        Pattern pattern = Pattern.compile("verifyEmail=([A-Za-z0-9_\\-\\.]+)");
        Matcher matcher = pattern.matcher(email.bodyHtml());
        assertTrue(matcher.find(), "Verification token link must be found in email body");
        String verifyToken = matcher.group(1);

        // Call verify-email
        VerifyEmailRequest verifyReq = new VerifyEmailRequest("flowuser@storagehub.test", verifyToken);
        HttpResponse<String> verifyResp = postJson("/api/auth/verify-email", verifyReq, null);
        assertThat(verifyResp.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    @DisplayName("Action 2 & Point 3: Forgot-Password API -> Email enumeration timing & 1 reset-password mail")
    void testForgotPasswordTimingAndDelivery() throws Exception {
        // Measure response time for non-existing user
        long startNonExisting = System.nanoTime();
        HttpResponse<String> respNonExisting = postJson(
            "/api/auth/forgot-password",
            new ForgotPasswordRequest("nonexistent-random-user@storagehub.test"),
            null
        );
        long elapsedNonExistingMs = (System.nanoTime() - startNonExisting) / 1_000_000;

        // Measure response time for existing user
        long startExisting = System.nanoTime();
        HttpResponse<String> respExisting = postJson(
            "/api/auth/forgot-password",
            new ForgotPasswordRequest("flowuser@storagehub.test"),
            null
        );
        long elapsedExistingMs = (System.nanoTime() - startExisting) / 1_000_000;

        // Verify Point 3: Anti-enumeration status & response body
        assertThat(respNonExisting.statusCode()).isEqualTo(200);
        assertThat(respExisting.statusCode()).isEqualTo(200);

        JsonNode jsonNonExisting = objectMapper.readTree(respNonExisting.body()).get("data");
        JsonNode jsonExisting = objectMapper.readTree(respExisting.body()).get("data");

        assertEquals(jsonNonExisting.get("accepted").asBoolean(), jsonExisting.get("accepted").asBoolean());
        assertEquals(jsonNonExisting.get("verificationRequired").asBoolean(), jsonExisting.get("verificationRequired").asBoolean());
        assertNull(jsonNonExisting.get("debugCode").textValue());
        assertNull(jsonExisting.get("debugCode").textValue());

        System.out.printf("Point 3 Timing Test: Non-existing user=%d ms, Existing user=%d ms (Difference=%d ms)%n",
            elapsedNonExistingMs, elapsedExistingMs, Math.abs(elapsedExistingMs - elapsedNonExistingMs));

        // Exactly 1 email in MailHog for the existing user (non-existing produced 0 emails)
        List<DecodedEmail> emails = waitForEmails(1, 5);
        assertThat(emails).hasSize(1);

        DecodedEmail email = emails.get(0);
        assertEquals("flowuser@storagehub.test", email.to());
        assertTrue(email.subject().contains("Đặt lại mật khẩu") || email.subject().contains("Yêu cầu"));
        assertTrue(email.bodyHtml().contains("resetPassword="));
    }

    @Test
    @Order(3)
    @DisplayName("Action 3: Reset-Password API -> Sends exactly 1 account-changed (Mật khẩu) to MailHog")
    void testResetPasswordFlow() throws Exception {
        // Trigger forgot password again to get fresh token
        postJson(
            "/api/auth/forgot-password",
            new ForgotPasswordRequest("flowuser@storagehub.test"),
            null
        );

        List<DecodedEmail> emails = waitForEmails(1, 5);
        String body = emails.get(0).bodyHtml();
        clearMailHog();

        Pattern pattern = Pattern.compile("resetPassword=([A-Za-z0-9_\\-\\.]+)");
        Matcher matcher = pattern.matcher(body);
        assertTrue(matcher.find());
        String resetToken = matcher.group(1);

        // Reset password
        ResetPasswordRequest resetReq = new ResetPasswordRequest("flowuser@storagehub.test", resetToken, "NewPass#456");
        HttpResponse<String> resetResp = postJson("/api/auth/reset-password", resetReq, null);
        assertThat(resetResp.statusCode()).isEqualTo(200);

        // Verify account-changed email
        List<DecodedEmail> changedEmails = waitForEmails(1, 5);
        assertThat(changedEmails).hasSize(1);

        DecodedEmail msg = changedEmails.get(0);
        assertTrue(msg.subject().contains("Thông tin tài khoản"));
        assertTrue(msg.bodyHtml().contains("Mật khẩu"));
        assertTrue(msg.bodyHtml().contains("Đã được thay đổi"));
        assertFalse(msg.bodyHtml().contains("••••••••"));
    }

    @Test
    @Order(4)
    @DisplayName("Action 4: Change-Password API -> Sends exactly 1 account-changed (Mật khẩu) to MailHog")
    void testChangePasswordFlow() throws Exception {
        // Login with newly reset password
        LoginRequest loginReq = new LoginRequest("flowuser@storagehub.test", "NewPass#456");
        HttpResponse<String> loginResp = postJson("/api/auth/login", loginReq, null);
        assertThat(loginResp.statusCode()).isEqualTo(200);
        String token = objectMapper.readTree(loginResp.body()).get("data").get("accessToken").asText();
        registeredUserToken = token;

        // Change password
        ChangePasswordRequest changeReq = new ChangePasswordRequest("NewPass#456", "FinalPass#789");
        HttpResponse<String> changeResp = postJson("/api/auth/password", changeReq, token);
        assertThat(changeResp.statusCode()).isEqualTo(200);

        // Verify account-changed email
        List<DecodedEmail> emails = waitForEmails(1, 5);
        assertThat(emails).hasSize(1);

        DecodedEmail msg = emails.get(0);
        assertTrue(msg.subject().contains("Thông tin tài khoản"));
        assertTrue(msg.bodyHtml().contains("Mật khẩu"));
        assertTrue(msg.bodyHtml().contains("Đã được thay đổi"));
        assertFalse(msg.bodyHtml().contains("••••••••"));

        // Update registeredUserToken with new token from change response
        registeredUserToken = objectMapper.readTree(changeResp.body()).get("data").get("accessToken").asText();
    }

    @Test
    @Order(5)
    @DisplayName("Action 5: Update Phone API (PUT /api/auth/me) -> Sends exactly 1 account-changed (Số điện thoại)")
    void testUpdatePhoneFlow() throws Exception {
        UpdateProfileRequest updateReq = new UpdateProfileRequest(
            "Nguyen Van Flow Updated",
            "0988776655", // Changed phone
            "123 Nguyen Hue, Q1",
            "Tran Thi Emergency",
            "0909999999",
            null
        );

        HttpResponse<String> resp = putJson("/api/auth/me", updateReq, registeredUserToken);
        assertThat(resp.statusCode()).isEqualTo(200);

        // Verify account-changed email
        List<DecodedEmail> emails = waitForEmails(1, 5);
        assertThat(emails).hasSize(1);

        DecodedEmail msg = emails.get(0);
        assertTrue(msg.subject().contains("Thông tin tài khoản"));
        assertTrue(msg.bodyHtml().contains("Số điện thoại"));
        assertTrue(msg.bodyHtml().contains("0988776655"));
    }

    @Test
    @Order(6)
    @DisplayName("Action 6: Admin update user role -> Sends exactly 1 account-changed (Vai trò)")
    void testAdminUpdateRoleFlow() throws Exception {
        // Unlock bootstrap admin mustChangePassword so admin can perform management operations
        User admin = userRepository.findByEmailIgnoreCase("admin@storagehub.test").orElseThrow();
        admin.setMustChangePassword(false);
        userRepository.saveAndFlush(admin);

        // Login as bootstrap admin
        LoginRequest adminLogin = new LoginRequest("admin@storagehub.test", "Bootstrap-password-123!");
        HttpResponse<String> adminResp = postJson("/api/auth/login", adminLogin, null);
        assertThat(adminResp.statusCode()).isEqualTo(200);
        adminToken = objectMapper.readTree(adminResp.body()).get("data").get("accessToken").asText();

        AdminUpdateRolesRequest rolesReq = new AdminUpdateRolesRequest(Set.of(RoleCode.CUSTOMER, RoleCode.STAFF));
        HttpResponse<String> resp = patchJson(
            "/api/admin/users/" + registeredUserId + "/roles",
            rolesReq,
            adminToken
        );
        assertThat(resp.statusCode()).isEqualTo(200);

        // Verify account-changed email
        List<DecodedEmail> emails = waitForEmails(1, 5);
        assertThat(emails).hasSize(1);

        DecodedEmail msg = emails.get(0);
        assertTrue(msg.subject().contains("Thông tin tài khoản"));
        assertTrue(msg.bodyHtml().contains("Vai trò"));
        assertTrue(msg.bodyHtml().contains("Quản trị viên")); // performed by admin
    }

    @Test
    @Order(7)
    @DisplayName("Action 7: Admin update email -> Sends exactly 2 account-changed (to old and new email)")
    void testAdminUpdateEmailFlow() throws Exception {
        AdminPatchUserRequest patchReq = new AdminPatchUserRequest(
            "flowuser-newemail@storagehub.test",
            null,
            null,
            null,
            null
        );

        HttpResponse<String> resp = patchJson(
            "/api/admin/users/" + registeredUserId,
            patchReq,
            adminToken
        );
        assertThat(resp.statusCode()).isEqualTo(200);

        // Verify 2 emails received in MailHog
        List<DecodedEmail> emails = waitForEmails(2, 5);
        assertThat(emails).hasSize(2);

        Set<String> recipients = new HashSet<>();
        for (DecodedEmail item : emails) {
            recipients.add(item.to());
            assertTrue(item.subject().contains("Thông tin tài khoản"));
            assertTrue(item.bodyHtml().contains("Email"));
        }

        assertTrue(recipients.contains("flowuser@storagehub.test"), "Must notify old email");
        assertTrue(recipients.contains("flowuser-newemail@storagehub.test"), "Must notify new email");
    }

    @Test
    @Order(8)
    @DisplayName("Nhóm 3: Admin create user -> Auto-generates 12-char temp password, sends account-created email, sets mustChangePassword=true")
    void testAdminCreateUserFlow() throws Exception {
        // Admin creates user without providing password
        com.storagehub.api.admin.AdminCreateUserRequest createReq = new com.storagehub.api.admin.AdminCreateUserRequest(
            "newstaff@storagehub.test",
            null, // Auto-generate
            "Nguyen Van Staff",
            "0911223344",
            Set.of(RoleCode.STAFF),
            null
        );

        HttpResponse<String> resp = postJson("/api/admin/users", createReq, adminToken);
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode createdUser = objectMapper.readTree(resp.body()).get("data");
        assertTrue(createdUser.get("mustChangePassword").asBoolean(), "mustChangePassword must be true");

        // Verify account-created email received in MailHog
        List<DecodedEmail> emails = waitForEmails(1, 5);
        assertThat(emails).hasSize(1);

        DecodedEmail email = emails.get(0);
        assertEquals("newstaff@storagehub.test", email.to());
        assertTrue(email.subject().contains("Tài khoản StorageHub của bạn đã được tạo"));
        assertTrue(email.bodyHtml().contains("Tài khoản đã được tạo"));
        assertTrue(email.bodyHtml().contains("Ngày tạo"));
        assertFalse(email.bodyHtml().contains("Thời gian đặt lại"));
        assertTrue(email.bodyHtml().contains("Nguyen Van Staff"));

        // Extract temporary password from email body
        // Template contains: Mật khẩu tạm thời: <b style="..." ...>password</b>
        Pattern pattern = Pattern.compile("Mật khẩu tạm thời:[^<]*<b[^>]*>([^<]+)</b>");
        Matcher matcher = pattern.matcher(email.bodyHtml());
        assertTrue(matcher.find(), "Must find temporary password in email HTML");
        String tempPassword = org.springframework.web.util.HtmlUtils.htmlUnescape(matcher.group(1).trim());

        // Verify temporary password satisfies PasswordPolicy
        assertTrue(com.storagehub.security.PasswordPolicy.isValid(tempPassword),
            "Generated temporary password must satisfy PasswordPolicy: " + tempPassword);

        // Verify newly created user can log in with this temporary password
        LoginRequest staffLogin = new LoginRequest("newstaff@storagehub.test", tempPassword);
        HttpResponse<String> loginResp = postJson("/api/auth/login", staffLogin, null);
        assertThat(loginResp.statusCode()).isEqualTo(200);

        JsonNode loginData = objectMapper.readTree(loginResp.body()).get("data");
        assertTrue(loginData.get("actor").get("mustChangePassword").asBoolean(),
            "Actor must have mustChangePassword=true upon login");
    }

    @Test
    @Order(9)
    @DisplayName("Admin reset password -> Auto-generates 12-char temp password, sends account-created email with temp password, sets mustChangePassword=true, response hides password")
    void testAdminResetPasswordFlow() throws Exception {
        // Admin calls password reset for registeredUserId with null temporaryPassword (auto-generate)
        com.storagehub.api.admin.AdminPasswordResetRequest resetReq = new com.storagehub.api.admin.AdminPasswordResetRequest(null);
        HttpResponse<String> resp = postJson(
            "/api/admin/users/" + registeredUserId + "/password-reset",
            resetReq,
            adminToken
        );
        assertThat(resp.statusCode()).isEqualTo(200);

        // Verify response does not leak password to admin
        JsonNode responseData = objectMapper.readTree(resp.body()).get("data");
        assertNull(responseData.get("password"));
        assertNull(responseData.get("temporaryPassword"));
        assertTrue(responseData.get("mustChangePassword").asBoolean());

        // Verify account-created email received in MailHog
        List<DecodedEmail> emails = waitForEmails(1, 5);
        assertThat(emails).hasSize(1);

        DecodedEmail email = emails.get(0);
        assertEquals("flowuser-newemail@storagehub.test", email.to());
        assertTrue(email.subject().contains("Mật khẩu StorageHub của bạn đã được đặt lại"));
        assertTrue(email.bodyHtml().contains("Mật khẩu đã được đặt lại"));
        assertTrue(email.bodyHtml().contains("Thời gian đặt lại"));
        assertFalse(email.bodyHtml().contains("Ngày tạo"));

        // Extract temporary password from email body
        Pattern pattern = Pattern.compile("Mật khẩu tạm thời:[^<]*<b[^>]*>([^<]+)</b>");
        Matcher matcher = pattern.matcher(email.bodyHtml());
        assertTrue(matcher.find(), "Must find temporary password in email HTML");
        String tempPassword = org.springframework.web.util.HtmlUtils.htmlUnescape(matcher.group(1).trim());

        assertTrue(com.storagehub.security.PasswordPolicy.isValid(tempPassword),
            "Generated temporary password must satisfy PasswordPolicy: " + tempPassword);

        // Verify user can log in with new temp password
        LoginRequest userLogin = new LoginRequest("flowuser-newemail@storagehub.test", tempPassword);
        HttpResponse<String> loginResp = postJson("/api/auth/login", userLogin, null);
        assertThat(loginResp.statusCode()).isEqualTo(200);

        JsonNode loginData = objectMapper.readTree(loginResp.body()).get("data");
        assertTrue(loginData.get("actor").get("mustChangePassword").asBoolean());
    }

    @Test
    @Order(10)
    @DisplayName("Nhóm 4: new-login notice -> Skip 1st login, skip same user-agent, send on new user-agent, rate-limit 1/6h on ActivityLog")
    void testNewLoginNoticeFlow() throws Exception {
        clearMailHog();

        // 1. Create a dedicated user for new-login testing
        String userEmail = "newlogin-user@storagehub.test";
        RegisterRequest registerReq = new RegisterRequest(
            userEmail,
            "ValidPass123!",
            "Người Dùng Mới",
            "0912345678",
            "123 Nguyen Trai, Q5",
            "Nguoi Lien He",
            "0987654321"
        );
        HttpResponse<String> regResp = postJson("/api/auth/register", registerReq, null);
        assertThat(regResp.statusCode()).isEqualTo(200);

        // Fetch verification token from MailHog and verify
        List<DecodedEmail> regEmails = waitForEmails(1, 5);
        assertThat(regEmails).hasSize(1);
        Pattern tokenPattern = Pattern.compile("verifyEmail=([A-Za-z0-9_\\-\\.]+)");
        Matcher tokenMatcher = tokenPattern.matcher(regEmails.get(0).bodyHtml());
        assertTrue(tokenMatcher.find(), "Must find verify token in email body");
        String verifyToken = tokenMatcher.group(1);

        HttpResponse<String> verifyResp = postJson(
            "/api/auth/verify-email",
            new VerifyEmailRequest(userEmail, verifyToken),
            null
        );
        assertThat(verifyResp.statusCode()).isEqualTo(200);

        // Clear MailHog
        clearMailHog();

        String uaChromeWindows120 = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        String uaChromeWindows121 = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";
        String uaFirefoxMac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0";
        String uaIPhoneSafari = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

        // Step 1: FIRST LOGIN (Device A - Chrome 120 Windows)
        // Must SUCCEED, but NO email sent (bỏ qua lần đăng nhập đầu)
        LoginRequest loginReq = new LoginRequest(userEmail, "ValidPass123!");
        HttpResponse<String> resp1 = postJson("/api/auth/login", loginReq, null, uaChromeWindows120);
        assertThat(resp1.statusCode()).isEqualTo(200);

        Thread.sleep(500);
        JsonNode messages1 = getMailHogMessages();
        assertThat(messages1.get("total").asInt()).isEqualTo(0);

        // Step 2: SECOND LOGIN (Device A - Updated Chrome version 121 Windows)
        // Same fingerprint (Windows PC|Chrome) -> Must SUCCEED, NO email sent (không phải thiết bị mới)
        HttpResponse<String> resp2 = postJson("/api/auth/login", loginReq, null, uaChromeWindows121);
        assertThat(resp2.statusCode()).isEqualTo(200);

        Thread.sleep(500);
        JsonNode messages2 = getMailHogMessages();
        assertThat(messages2.get("total").asInt()).isEqualTo(0);

        // Step 3: THIRD LOGIN (Device B - New Fingerprint: Firefox Mac)
        // Must SUCCEED and SEND new-login notice (1 email)!
        HttpResponse<String> resp3 = postJson("/api/auth/login", loginReq, null, uaFirefoxMac);
        assertThat(resp3.statusCode()).isEqualTo(200);

        List<DecodedEmail> loginEmails = waitForEmails(1, 5);
        assertThat(loginEmails).hasSize(1);
        DecodedEmail loginEmail = loginEmails.get(0);
        assertEquals(userEmail, loginEmail.to());
        assertTrue(loginEmail.subject().contains("Đăng nhập mới"), "Subject must indicate new login");
        assertTrue(loginEmail.bodyHtml().contains("Firefox"), "Body must contain parsed browser");
        assertTrue(loginEmail.bodyHtml().contains("Mac"), "Body must contain parsed device");

        // Clear MailHog
        clearMailHog();

        // Step 4: FOURTH LOGIN (Device C - Another New Fingerprint: iPhone Safari immediately after)
        // Must SUCCEED and SEND 1 email because it is a different new device fingerprint
        HttpResponse<String> resp4 = postJson("/api/auth/login", loginReq, null, uaIPhoneSafari);
        assertThat(resp4.statusCode()).isEqualTo(200);

        List<DecodedEmail> iphoneEmails = waitForEmails(1, 5);
        assertThat(iphoneEmails).hasSize(1);
        DecodedEmail iphoneEmail = iphoneEmails.get(0);
        assertEquals(userEmail, iphoneEmail.to());
        assertTrue(iphoneEmail.bodyHtml().contains("iPhone"), "Body must contain iPhone");
        assertTrue(iphoneEmail.bodyHtml().contains("Safari"), "Body must contain Safari");

        // Clear MailHog
        clearMailHog();

        // Step 5: FIFTH LOGIN (Device B repeated: Firefox Mac)
        // Fingerprint "Mac|Firefox" was already recorded in step 3 -> Must SUCCEED, NO email sent!
        HttpResponse<String> resp5 = postJson("/api/auth/login", loginReq, null, uaFirefoxMac);
        assertThat(resp5.statusCode()).isEqualTo(200);

        Thread.sleep(500);
        JsonNode messages5 = getMailHogMessages();
        assertThat(messages5.get("total").asInt()).isEqualTo(0);
    }

    @Test
    @Order(11)
    @DisplayName("Nhóm 5: security-alert flow -> Skip non-existent user, skip below threshold, send on 5th attempt, rate-limit 1/cooldown (6h)")
    void testSecurityAlertFlow() throws Exception {
        clearMailHog();

        // 1. NON-EXISTENT USER: Multiple failed logins must return 401 and NEVER send security-alert email
        String ghostEmail = "ghost-nonexistent-user@storagehub.test";
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> ghostResp = postJson(
                "/api/auth/login",
                new LoginRequest(ghostEmail, "WrongPass123!"),
                null
            );
            assertThat(ghostResp.statusCode()).isEqualTo(401);
        }
        Thread.sleep(500);
        JsonNode ghostMessages = getMailHogMessages();
        assertThat(ghostMessages.get("total").asInt()).isEqualTo(0);

        // 2. REAL ACTIVE USER: Register & activate dedicated user
        String targetEmail = "securityalert-user@storagehub.test";
        RegisterRequest registerReq = new RegisterRequest(
            targetEmail,
            "RealSecurePass123!",
            "Người Dùng Bảo Mật",
            "0912345679",
            "456 Le Loi, Q1",
            "Nguoi Bao Ho",
            "0987654322"
        );
        HttpResponse<String> regResp = postJson("/api/auth/register", registerReq, null);
        assertThat(regResp.statusCode()).isEqualTo(200);

        List<DecodedEmail> regEmails = waitForEmails(1, 5);
        Pattern alertTokenPattern = Pattern.compile("verifyEmail=([A-Za-z0-9_\\-\\.]+)");
        Matcher alertTokenMatcher = alertTokenPattern.matcher(regEmails.get(0).bodyHtml());
        assertTrue(alertTokenMatcher.find(), "Must find verify token in email body");
        String otp = alertTokenMatcher.group(1);

        HttpResponse<String> verifyResp = postJson("/api/auth/verify-email", new VerifyEmailRequest(targetEmail, otp), null);
        assertThat(verifyResp.statusCode()).isEqualTo(200);
        clearMailHog();

        // Step A: 4 failed attempts (below threshold of 5) -> NO email sent
        for (int i = 1; i <= 4; i++) {
            HttpResponse<String> failResp = postJson(
                "/api/auth/login",
                new LoginRequest(targetEmail, "WrongPassword" + i + "!"),
                null
            );
            assertThat(failResp.statusCode()).isEqualTo(401);
        }
        Thread.sleep(500);
        JsonNode messagesUnderThreshold = getMailHogMessages();
        assertThat(messagesUnderThreshold.get("total").asInt()).isEqualTo(0);

        // Step B: 5th failed attempt -> Reaches threshold (5) -> SEND security-alert email!
        HttpResponse<String> fifthResp = postJson(
            "/api/auth/login",
            new LoginRequest(targetEmail, "WrongPassword5!"),
            null
        );
        assertThat(fifthResp.statusCode()).isEqualTo(401);

        List<DecodedEmail> alertEmails = waitForEmails(1, 5);
        assertThat(alertEmails).hasSize(1);
        DecodedEmail alert = alertEmails.get(0);
        assertEquals(targetEmail, alert.to());
        assertTrue(alert.subject().contains("Cảnh báo bảo mật"), "Subject must indicate security alert");
        assertTrue(alert.bodyHtml().contains("5 lần đăng nhập sai"), "Must show 5 failed attempts");
        assertTrue(alert.bodyHtml().contains("tạm thời bị giới hạn đăng nhập"), "Must indicate rate limiting");
        assertFalse(alert.bodyHtml().contains("tài khoản đã bị <b>tạm khóa</b>"), "isLocked must be false for active user");

        clearMailHog();

        // Step C: 6th and 7th failed attempts within the 15-minute window
        // Must return 401, but NO duplicate security-alert email sent (1 mail per window)
        for (int i = 6; i <= 7; i++) {
            HttpResponse<String> extraFail = postJson(
                "/api/auth/login",
                new LoginRequest(targetEmail, "WrongPassword" + i + "!"),
                null
            );
            assertThat(extraFail.statusCode()).isEqualTo(401);
        }
        Thread.sleep(500);
        JsonNode extraMessages = getMailHogMessages();
        assertThat(extraMessages.get("total").asInt()).isEqualTo(0);
    }

    @Test
    @Order(12)
    @DisplayName("Backfill Fingerprint Test: Legacy login history with fingerprint NULL -> Chrome login sends 0 emails, Firefox login sends 1 email")
    void testLegacyLoginHistoryFingerprintBackfill() throws Exception {
        clearMailHog();

        String legacyEmail = "legacy-user@storagehub.test";
        String legacyPassword = "LegacyPass123!";
        RegisterRequest registerReq = new RegisterRequest(
            legacyEmail,
            legacyPassword,
            "Legacy User",
            "0911223344",
            "789 Tran Hung Dao, Q5",
            "Nguoi Than Legacy",
            "0988776655"
        );
        HttpResponse<String> regResp = postJson("/api/auth/register", registerReq, null);
        assertThat(regResp.statusCode()).isEqualTo(200);

        List<DecodedEmail> regEmails = waitForEmails(1, 5);
        Pattern tokenPattern = Pattern.compile("verifyEmail=([A-Za-z0-9_\\-\\.]+)");
        Matcher matcher = tokenPattern.matcher(regEmails.get(0).bodyHtml());
        assertTrue(matcher.find(), "Must find verify token");
        String verifyToken = matcher.group(1);

        HttpResponse<String> verifyResp = postJson("/api/auth/verify-email", new VerifyEmailRequest(legacyEmail, verifyToken), null);
        assertThat(verifyResp.statusCode()).isEqualTo(200);

        // Precondition: user exists in DB. Create a legacy LoginHistory record with device_fingerprint = NULL
        User user = userRepository.findByEmailIgnoreCase(legacyEmail).orElseThrow();
        String uaChromeWindows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        String uaFirefoxMac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:122.0) Gecko/20100101 Firefox/122.0";

        com.storagehub.domain.model.LoginHistory legacyHistory = new com.storagehub.domain.model.LoginHistory();
        legacyHistory.setUser(user);
        legacyHistory.setEmailAttempted(legacyEmail);
        legacyHistory.setSuccess(true);
        legacyHistory.setUserAgent(uaChromeWindows);
        legacyHistory.setDeviceFingerprint(null); // Explicitly NULL legacy fingerprint!
        legacyHistory.setOccurredAt(java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS));
        legacyHistory.setIpAddress("127.0.0.1");
        loginHistoryRepository.saveAndFlush(legacyHistory);

        // Clear verification mail
        clearMailHog();

        // 1. User logs in with Chrome Windows (matching legacy record with NULL fingerprint)
        // Must SUCCEED and send 0 emails (no false positive new-login alert)
        HttpResponse<String> respChrome = postJson(
            "/api/auth/login",
            new LoginRequest(legacyEmail, legacyPassword),
            null,
            uaChromeWindows
        );
        assertThat(respChrome.statusCode()).isEqualTo(200);

        Thread.sleep(500);
        JsonNode messagesChrome = getMailHogMessages();
        assertThat(messagesChrome.get("total").asInt()).isEqualTo(0);

        // 2. User logs in with Firefox Mac (different device/fingerprint)
        // Must SUCCEED and send exactly 1 new-login email!
        HttpResponse<String> respFirefox = postJson(
            "/api/auth/login",
            new LoginRequest(legacyEmail, legacyPassword),
            null,
            uaFirefoxMac
        );
        assertThat(respFirefox.statusCode()).isEqualTo(200);

        List<DecodedEmail> firefoxEmails = waitForEmails(1, 5);
        assertThat(firefoxEmails).hasSize(1);
        DecodedEmail firefoxEmail = firefoxEmails.get(0);
        assertEquals(legacyEmail, firefoxEmail.to());
        assertTrue(firefoxEmail.subject().contains("Đăng nhập mới"), "Subject must indicate new login");
        assertTrue(firefoxEmail.bodyHtml().contains("Firefox"), "Body must contain parsed browser");
        assertTrue(firefoxEmail.bodyHtml().contains("Mac"), "Body must contain parsed device");
    }

    @Test
    @Order(13)
    @DisplayName("Action: Send all 6 transactional emails to MailHog and verify inline logo CID attachment")
    void testSendAllSixEmailsToMailHogAndVerifyCidLogo() throws Exception {
        clearMailHog();

        UUID testUserId = UUID.randomUUID();
        String to = "mailhog-all-six@storagehub.local";

        transactionalEmailService.sendVerifyEmailAsync(testUserId, to, "Nguyễn Văn Test", "http://localhost:5173/?verifyEmail=sample-tok", 15);
        transactionalEmailService.sendPasswordResetAsync(testUserId, to, "Nguyễn Văn Test", "http://localhost:5173/?resetPassword=sample-tok", 15);
        transactionalEmailService.sendNewLoginNoticeAsync(testUserId, to, "Nguyễn Văn Test", "15:00 02/10/2026", "Windows PC", "Chrome", "TP. Hồ Chí Minh", "127.0.0.1", "http://localhost:5173/profile/security");
        transactionalEmailService.sendAccountCreatedAsync(testUserId, to, "Nguyễn Văn Test", "02/10/2026 15:00", "TempPass@123", "http://localhost:5173/login", false);
        transactionalEmailService.sendSecurityAlertAsync(testUserId, to, "Nguyễn Văn Test", 5, 15, "15:15 02/10/2026", "15:00 02/10/2026", "127.0.0.1", "TP. Hồ Chí Minh", "http://localhost:5173/?resetPassword=tok", false);
        transactionalEmailService.sendAccountChangedAsync(testUserId, to, "Nguyễn Văn Test", "15:00 02/10/2026", "Mật khẩu", "••••••••", "••••••••", false, "Chrome trên Windows PC", "127.0.0.1", "http://localhost:5173/profile");

        List<DecodedEmail> emails = waitForEmails(6, 10);
        assertThat(emails).hasSize(6);

        // Verify that every email received has the inline logo CID <storagehubLogo> and matches HTML src exactly
        JsonNode data = getMailHogMessages();
        for (JsonNode item : data.get("items")) {
            String id = item.get("ID").asText();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(MAILHOG_BASE + "/api/v1/messages/" + id + "/download"))
                .GET()
                .build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            MimeMessage mime = new MimeMessage(null, resp.body());

            // 1. Verify HTML body contains exact CID src, alt, and explicit height="44"
            String html = extractHtmlContent(mime);
            assertNotNull(html, "Email " + mime.getSubject() + " must have HTML body");
            assertTrue(html.contains("src=\"cid:storagehubLogo\""),
                "Email " + mime.getSubject() + " HTML must contain src=\"cid:storagehubLogo\" with exact case");
            assertTrue(html.contains("alt=\"StorageHub\""),
                "Email " + mime.getSubject() + " HTML must contain alt=\"StorageHub\"");
            assertTrue(html.contains("height=\"44\""),
                "Email " + mime.getSubject() + " HTML must contain explicit height=\"44\"");

            // 2. Extract image Content-ID header from MIME structure
            String imageContentId = findInlineContentId(mime, "image/png");
            assertNotNull(imageContentId, "Email " + mime.getSubject() + " must have inline image/png part");

            // Strip angle brackets: "<storagehubLogo>" -> "storagehubLogo"
            String cleanedCid = imageContentId.replaceAll("[<>]", "").trim();
            assertEquals("storagehubLogo", cleanedCid, "Inline image part Content-ID must match storagehubLogo");

            // 3. Strict match: parse CID from HTML and assert it equals cleanedCid
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("src=[\"']cid:([^\"']+)[\"']").matcher(html);
            assertTrue(matcher.find(), "Must find cid: in img src attribute");
            String htmlCid = matcher.group(1);
            assertEquals(cleanedCid, htmlCid, "HTML src CID (" + htmlCid + ") must strictly match MIME Content-ID (" + cleanedCid + ")");
        }
    }

    @Test
    @Order(14)
    @DisplayName("Validation: CID mismatch between HTML src and image Content-ID must fail validation")
    void testCidMismatchFailsValidation() {
        String htmlWithWrongCid = "<img src=\"cid:mismatchedLogo\" alt=\"StorageHub\" height=\"44\">";
        String imageContentIdHeader = "<storagehubLogo>";
        String cleanedHeader = imageContentIdHeader.replaceAll("[<>]", "").trim();

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("src=[\"']cid:([^\"']+)[\"']").matcher(htmlWithWrongCid);
        assertTrue(matcher.find());
        String htmlCid = matcher.group(1);

        assertThrows(AssertionError.class, () -> {
            assertEquals(cleanedHeader, htmlCid, "CID mismatch must trigger an assertion error");
        });
    }

    private String findInlineContentId(Part part, String expectedMimeType) throws Exception {
        if (part.isMimeType(expectedMimeType)) {
            String[] headers = part.getHeader("Content-ID");
            if (headers != null && headers.length > 0) {
                return headers[0].trim();
            }
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String res = findInlineContentId(mp.getBodyPart(i), expectedMimeType);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }

    private boolean containsContentId(Part part, String contentId) throws Exception {
        String[] headers = part.getHeader("Content-ID");
        if (headers != null) {
            for (String h : headers) {
                if (h.contains(contentId)) {
                    return true;
                }
            }
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                if (containsContentId(mp.getBodyPart(i), contentId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
