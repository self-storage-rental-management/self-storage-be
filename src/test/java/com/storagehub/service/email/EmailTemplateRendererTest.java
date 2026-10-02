package com.storagehub.service.email;

import com.storagehub.config.EmailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailTemplateRendererTest {

    private EmailTemplateRenderer renderer;
    private EmailProperties emailProperties;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        emailProperties = new EmailProperties();
        emailProperties.setSupportEmail("support@storagehub.vn");
        emailProperties.setCompanyAddress("Tòa nhà StorageHub, Khu Công Nghệ Cao, TP.HCM");
        emailProperties.setLogoUrl("https://storagehub.vn/assets/logo.png");

        renderer = new EmailTemplateRenderer(engine, emailProperties);
    }

    @Test
    @DisplayName("1. verify-email template renders correctly with UTF-8 Vietnamese and escaped inputs")
    void testVerifyEmailTemplate() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", "Nguyễn Văn Tuấn <script>alert(1)</script>");
        vars.put("email", "tuan@example.com");
        vars.put("verifyUrl", "http://localhost:5173/?verifyEmail=token123");
        vars.put("expiryMinutes", 15);

        String html = renderer.render("verify-email", vars);

        assertNotNull(html);
        assertTrue(html.contains("Xác minh địa chỉ email"), "Must contain Vietnamese header");
        assertTrue(html.contains("tuan@example.com"), "Must contain email");
        assertTrue(html.contains("http://localhost:5173/?verifyEmail=token123"), "Must contain verifyUrl");
        assertTrue(html.contains("15 phút"), "Must contain expiry minutes");
        assertTrue(html.contains("support@storagehub.vn"), "Must contain support email");
        assertTrue(html.contains("Tòa nhà StorageHub"), "Must contain company address");
        // XSS escape check
        assertFalse(html.contains("<script>alert(1)</script>"), "Must NOT contain unescaped script tag");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;") || html.contains("&#39;"), "Must be HTML escaped");
    }

    @Test
    @DisplayName("2. reset-password template renders properly")
    void testResetPasswordTemplate() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", "Trần Thị B");
        vars.put("email", "b.tran@example.com");
        vars.put("resetUrl", "http://localhost:5173/?resetPassword=resetToken456");
        vars.put("expiryMinutes", 15);

        String html = renderer.render("reset-password", vars);

        assertNotNull(html);
        assertTrue(html.contains("Đặt lại mật khẩu"), "Must contain title");
        assertTrue(html.contains("Trần Thị B"), "Must contain fullName");
        assertTrue(html.contains("http://localhost:5173/?resetPassword=resetToken456"), "Must contain resetUrl");
        assertTrue(html.contains("Không phải bạn yêu cầu?"), "Must contain warning box");
    }

    @Test
    @DisplayName("3. new-login template renders properly")
    void testNewLoginTemplate() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("fullName", "Lê Văn C");
        vars.put("loginTime", "14:20 02/10/2026");
        vars.put("device", "Windows PC");
        vars.put("browser", "Chrome");
        vars.put("location", "Không xác định");
        vars.put("ipAddress", "14.162.1.20");
        vars.put("securityUrl", "http://localhost:5173/profile/security");

        String html = renderer.render("new-login", vars);

        assertNotNull(html);
        assertTrue(html.contains("Có lượt đăng nhập mới"));
        assertTrue(html.contains("14:20 02/10/2026"));
        assertTrue(html.contains("Windows PC"));
        assertTrue(html.contains("Chrome"));
        assertTrue(html.contains("14.162.1.20"));
        assertTrue(html.contains("Không xác định"));
        assertTrue(html.contains("Bảo vệ tài khoản"));
    }

    @Test
    @DisplayName("4. account-created template handles both account creation and admin reset modes")
    void testAccountCreatedTemplate() {
        // Case A: Admin created new account (isReset = false)
        Map<String, Object> varsCreate = new HashMap<>();
        varsCreate.put("fullName", "Phạm Văn D");
        varsCreate.put("email", "d.pham@example.com");
        varsCreate.put("createdAt", "02/10/2026 15:00");
        varsCreate.put("tempPassword", "TempPass@999");
        varsCreate.put("loginUrl", "http://localhost:5173/login");
        varsCreate.put("isReset", false);

        String htmlCreate = renderer.render("account-created", varsCreate);
        assertNotNull(htmlCreate);
        assertTrue(htmlCreate.contains("Tài khoản đã được tạo"));
        assertTrue(htmlCreate.contains("Tài khoản StorageHub của bạn đã sẵn sàng"));
        assertTrue(htmlCreate.contains("Ngày tạo"));
        assertFalse(htmlCreate.contains("Thời gian đặt lại"));
        assertTrue(htmlCreate.contains("TempPass@999"), "Must display temporary password");
        assertTrue(htmlCreate.contains("Tài khoản này do quản trị viên cấp"));

        // Case B: Admin reset password (isReset = true)
        Map<String, Object> varsReset = new HashMap<>();
        varsReset.put("fullName", "Phạm Văn D");
        varsReset.put("email", "d.pham@example.com");
        varsReset.put("createdAt", "02/10/2026 15:00");
        varsReset.put("resetTime", "02/10/2026 15:00");
        varsReset.put("tempPassword", "TempPass@999");
        varsReset.put("loginUrl", "http://localhost:5173/login");
        varsReset.put("isReset", true);

        String htmlReset = renderer.render("account-created", varsReset);
        assertNotNull(htmlReset);
        assertTrue(htmlReset.contains("Mật khẩu đã được đặt lại"));
        assertTrue(htmlReset.contains("Mật khẩu tài khoản StorageHub của bạn đã được quản trị viên đặt lại"));
        assertTrue(htmlReset.contains("Thời gian đặt lại"));
        assertFalse(htmlReset.contains("Ngày tạo"));
        assertTrue(htmlReset.contains("TempPass@999"), "Must display temporary password");

        // Case C: Without temp password (self-registered or no temp password)
        Map<String, Object> varsWithoutPassword = new HashMap<>();
        varsWithoutPassword.put("fullName", "Phạm Văn D");
        varsWithoutPassword.put("email", "d.pham@example.com");
        varsWithoutPassword.put("createdAt", "02/10/2026 15:00");
        varsWithoutPassword.put("tempPassword", null);
        varsWithoutPassword.put("loginUrl", "http://localhost:5173/login");
        varsWithoutPassword.put("isReset", false);

        String htmlWithoutPassword = renderer.render("account-created", varsWithoutPassword);
        assertNotNull(htmlWithoutPassword);
        assertFalse(htmlWithoutPassword.contains("Tài khoản này do quản trị viên cấp"), "Should NOT display temp password notice when null");
        assertFalse(htmlWithoutPassword.contains("TempPass@999"));
    }

    @Test
    @DisplayName("5. security-alert template displays rate limit vs locked accurately")
    void testSecurityAlertTemplate() {
        // Case A: Rate limited (isLocked = false)
        Map<String, Object> varsRateLimited = new HashMap<>();
        varsRateLimited.put("fullName", "Hoàng Văn E");
        varsRateLimited.put("failedCount", 10);
        varsRateLimited.put("windowMinutes", 15);
        varsRateLimited.put("unlockTime", "15:30 02/10/2026");
        varsRateLimited.put("lastAttemptTime", "15:15 02/10/2026");
        varsRateLimited.put("ipAddress", "118.69.1.5");
        varsRateLimited.put("location", "Không xác định");
        varsRateLimited.put("resetUrl", "http://localhost:5173/?resetPassword=abc");
        varsRateLimited.put("isLocked", false);

        String htmlRateLimited = renderer.render("security-alert", varsRateLimited);
        assertNotNull(htmlRateLimited);
        assertTrue(htmlRateLimited.contains("tạm thời bị giới hạn đăng nhập"), "Must indicate rate limiting");
        assertFalse(htmlRateLimited.contains("tài khoản đã bị <b>tạm khóa</b>"));

        // Case B: UserStatus.LOCKED (isLocked = true)
        varsRateLimited.put("isLocked", true);
        String htmlLocked = renderer.render("security-alert", varsRateLimited);
        assertTrue(htmlLocked.contains("tài khoản đã bị <b>tạm khóa</b>"), "Must indicate actual account lock");
    }

    @Test
    @DisplayName("6. account-changed template handles admin vs user performer")
    void testAccountChangedTemplate() {
        // Case A: Performed by Admin
        Map<String, Object> adminVars = new HashMap<>();
        adminVars.put("fullName", "Đỗ Văn F");
        adminVars.put("changeTime", "16:00 02/10/2026");
        adminVars.put("fieldName", "Vai trò");
        adminVars.put("oldValue", "CUSTOMER");
        adminVars.put("newValue", "STAFF");
        adminVars.put("performedByAdmin", true);
        adminVars.put("accountUrl", "http://localhost:5173/profile");

        String htmlAdmin = renderer.render("account-changed", adminVars);
        assertNotNull(htmlAdmin);
        assertTrue(htmlAdmin.contains("Thông tin tài khoản đã thay đổi"));
        assertTrue(htmlAdmin.contains("Quản trị viên"), "Must display Quản trị viên when performed by admin");
        assertTrue(htmlAdmin.contains("CUSTOMER"));
        assertTrue(htmlAdmin.contains("STAFF"));

        // Case B: Performed by User
        Map<String, Object> userVars = new HashMap<>();
        userVars.put("fullName", "Đỗ Văn F");
        userVars.put("changeTime", "16:00 02/10/2026");
        userVars.put("fieldName", "Mật khẩu");
        userVars.put("oldValue", "••••••••");
        userVars.put("newValue", "••••••••");
        userVars.put("performedByAdmin", false);
        userVars.put("device", "Chrome trên Windows PC");
        userVars.put("ipAddress", "192.168.1.50");
        userVars.put("accountUrl", "http://localhost:5173/profile");

        String htmlUser = renderer.render("account-changed", userVars);
        assertNotNull(htmlUser);
        assertFalse(htmlUser.contains("Quản trị viên"));
        assertTrue(htmlUser.contains("Chrome trên Windows PC · 192.168.1.50"));
    }

    @Test
    @DisplayName("7. UserAgentParser tests")
    void testUserAgentParser() {
        var winChrome = UserAgentParser.parse("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36");
        assertEquals("Windows PC", winChrome.device());
        assertEquals("Chrome", winChrome.browser());
        assertEquals("Chrome trên Windows PC", winChrome.summary());

        var iPhoneSafari = UserAgentParser.parse("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1");
        assertEquals("iPhone", iPhoneSafari.device());
        assertEquals("Safari", iPhoneSafari.browser());

        var unknown = UserAgentParser.parse("");
        assertEquals("Không xác định", unknown.device());
        assertEquals("Không xác định", unknown.browser());
    }
}
