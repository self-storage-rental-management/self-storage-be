package com.storagehub.service;

import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ApiExceptions;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationCaptchaService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public Map<String, String> issue(String clientAddress) {
        Instant now = Instant.now();
        String clientHash = hash(clientAddress == null ? "unknown" : clientAddress);
        Long recent = jdbc.queryForObject(
            "SELECT COUNT(*) FROM registration_captchas WHERE client_hash = ? AND created_at > ?",
            Long.class, clientHash, Timestamp.from(now.minus(5, ChronoUnit.MINUTES)));
        if (recent != null && recent >= 20) {
            throw ApiExceptions.conflict("Bạn đã yêu cầu quá nhiều CAPTCHA. Vui lòng thử lại sau 5 phút.");
        }
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 6; i++) answer.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        String id = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(5, ChronoUnit.MINUTES);
        String image = render(answer.toString());
        jdbc.update("INSERT INTO registration_captchas (id, answer_hash, client_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            id, hash(id + ":" + answer), clientHash, Timestamp.from(now), Timestamp.from(expiresAt));
        return Map.of("captchaId", id, "imageDataUrl", image, "expiresAt", expiresAt.toString());
    }

    // Consume independently so failed registration and wrong answers cannot replay the challenge.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void verify(String id, String answer) {
        if (id == null || answer == null || id.length() != 36 || answer.trim().length() != 6) throw invalid();
        var rows = jdbc.query("SELECT answer_hash, expires_at FROM registration_captchas WHERE id = ? FOR UPDATE",
            (rs, row) -> Map.entry(rs.getString("answer_hash"), rs.getTimestamp("expires_at").toInstant()), id);
        if (rows.isEmpty()) throw invalid();
        var challenge = rows.get(0);
        // Keep the row until expiry so consumption cannot bypass issuance throttling.
        jdbc.update("UPDATE registration_captchas SET answer_hash = '' WHERE id = ?", id);
        String supplied = hash(id + ":" + answer.trim().toUpperCase(Locale.ROOT));
        if (!challenge.getValue().isAfter(Instant.now()) || !MessageDigest.isEqual(
            challenge.getKey().getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) throw invalid();
    }

    @Scheduled(fixedDelay = 60000)
    public void removeExpired() {
        jdbc.update("DELETE FROM registration_captchas WHERE expires_at < ?", Timestamp.from(Instant.now()));
    }

    private ApiException invalid() {
        return ApiExceptions.validation("CAPTCHA không đúng, đã dùng hoặc đã hết hạn. Vui lòng lấy mã mới.",
            Map.of("captchaAnswer", "Vui lòng hoàn thành CAPTCHA mới trước khi đăng ký."));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String render(String answer) {
        BufferedImage image = new BufferedImage(240, 76, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(250, 247, 238));
            graphics.fillRect(0, 0, 240, 76);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < 12; i++) {
                graphics.setColor(new Color(150 + random.nextInt(80), 150 + random.nextInt(80), 150 + random.nextInt(80)));
                graphics.drawLine(random.nextInt(240), random.nextInt(76), random.nextInt(240), random.nextInt(76));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            for (int i = 0; i < answer.length(); i++) {
                var transform = graphics.getTransform();
                graphics.rotate((random.nextDouble() - 0.5) * 0.35, 23 + i * 37, 40);
                graphics.setColor(new Color(25 + random.nextInt(60), 25 + random.nextInt(60), 25 + random.nextInt(60)));
                graphics.drawString(answer.substring(i, i + 1), 12 + i * 37, 47 + random.nextInt(9));
                graphics.setTransform(transform);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot create registration CAPTCHA", exception);
        } finally {
            graphics.dispose();
        }
    }
}
