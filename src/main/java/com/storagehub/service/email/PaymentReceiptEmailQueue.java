package com.storagehub.service.email;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable transactional mail. SMTP is called outside database transactions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReceiptEmailQueue {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;
    private final com.storagehub.config.JwtProperties jwtProperties;
    private final SecureRandom random = new SecureRandom();
    private static final String PROTECTED_PAYLOAD = "enc:v1:";

    @Transactional
    public void enqueue(UUID userId, String recipient, Map<String, Object> payload) {
        enqueue(userId, recipient, "payment-bill", "StorageHub - Biên lai xác nhận thanh toán thành công",
            "PAYMENT_BILL_EMAIL_SENT", recipient + ":" + payload.get("transactionNo") + ":" + payload.get("invoiceNumber") + ":" + payload.get("paymentPurpose"), payload);
    }

    @Transactional
    public void enqueue(UUID userId, String recipient, String template, String subject, String auditAction,
                        String eventKey, Map<String, Object> payload) {
        if (recipient == null || recipient.isBlank()) throw new IllegalArgumentException("Email recipient is required");
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((recipient + ":" + eventKey).getBytes(StandardCharsets.UTF_8)));
            Instant expiry = payload.get("_expiresAt") == null ? null : Instant.parse(payload.get("_expiresAt").toString());
            if (java.util.Set.of("verify-email", "reset-password").contains(template)) {
                jdbc.update("UPDATE payment_receipt_emails SET delivery_status='SUPERSEDED',payload='{}',last_error='NEW_CHALLENGE_ISSUED' WHERE recipient=? AND template_name=? AND delivery_status='PENDING' AND (locked_until IS NULL OR locked_until<?)",
                    recipient, template, Timestamp.from(Instant.now()));
            }
            String body = mapper.writeValueAsString(payload);
            if (java.util.Set.of("verify-email", "reset-password", "reservation-verification").contains(template)) body = protect(body);
            jdbc.update("INSERT INTO payment_receipt_emails (receipt_key,user_id,recipient,payload,template_name,subject,audit_action,expires_at) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE receipt_key=receipt_key",
                key, userId == null ? null : userId.toString(), recipient, body, template, subject, auditAction,
                expiry == null ? null : Timestamp.from(expiry));
        } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Cannot queue transactional email", exception);
        }
    }

    public void deliverPending(TransactionalEmailService sender) {
        var ids = jdbc.query("SELECT id FROM payment_receipt_emails WHERE delivery_status='PENDING' AND next_attempt_at<=? AND (locked_until IS NULL OR locked_until<?) ORDER BY id LIMIT 20",
            (rs, row) -> rs.getLong(1), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        for (Long id : ids) {
            Entry entry = transaction().execute(status -> claim(id));
            if (entry == null) continue;
            boolean sent = false;
            String failure = sender.deliveryUnavailableReason();
            try {
                Map<String, Object> payload = mapper.readValue(unprotect(entry.payload()), new TypeReference<Map<String, Object>>() {});
                if (failure == null) {
                    sent = sender.deliverEmail(entry.userId() == null ? null : UUID.fromString(entry.userId()),
                        entry.recipient(), entry.subject(), entry.template(), payload, entry.auditAction());
                    if (!sent) failure = "SMTP_FAILED";
                }
            } catch (Exception exception) {
                failure = "DELIVERY_FAILED";
                log.warn("Email delivery failed: queueId={}, exception={}", entry.id(), exception.getClass().getSimpleName());
            }
            final boolean delivered = sent;
            final String lastError = failure;
            transaction().executeWithoutResult(status -> {
                Instant now = Instant.now();
                long retrySeconds = Math.min(3600, 30L << Math.min(entry.attempts(), 7));
                jdbc.update("UPDATE payment_receipt_emails SET attempts=attempts+1,sent_at=?,delivery_status=?,next_attempt_at=?,last_error=?,locked_until=NULL,lock_token=NULL,payload=CASE WHEN ? AND template_name IN ('verify-email','reset-password','reservation-verification') THEN '{}' ELSE payload END WHERE id=? AND lock_token=?",
                    delivered ? Timestamp.from(now) : null, delivered ? "SENT" : "PENDING", Timestamp.from(now.plusSeconds(retrySeconds)),
                    lastError, delivered, entry.id(), entry.token());
            });
        }
    }

    private Entry claim(long id) {
        Instant now = Instant.now();
        var rows = jdbc.query("SELECT id,user_id,recipient,payload,attempts,template_name,subject,audit_action,expires_at FROM payment_receipt_emails WHERE id=? AND delivery_status='PENDING' AND next_attempt_at<=? AND (locked_until IS NULL OR locked_until<?) FOR UPDATE SKIP LOCKED",
            (rs, row) -> new Entry(rs.getLong("id"), rs.getString("user_id"), rs.getString("recipient"), rs.getString("payload"),
                rs.getInt("attempts"), rs.getString("template_name"), rs.getString("subject"), rs.getString("audit_action"),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(), UUID.randomUUID().toString()),
            id, Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) return null;
        Entry entry = rows.getFirst();
        if (entry.expiresAt() != null && !entry.expiresAt().isAfter(now)) {
            jdbc.update("UPDATE payment_receipt_emails SET delivery_status='EXPIRED',last_error='MESSAGE_EXPIRED',payload='{}',locked_until=NULL,lock_token=NULL WHERE id=?", id);
            return null;
        }
        if (entry.template().equals("customer-lifecycle")) try {
            var payload = mapper.readValue(entry.payload(), new TypeReference<Map<String, Object>>() {});
            if (payload.get("_requiredReservationStatus") != null && payload.get("_reservationId") != null) {
                var states = jdbc.query("SELECT status FROM reservations WHERE id=UNHEX(REPLACE(?,'-',''))", (rs, row) -> rs.getString(1), payload.get("_reservationId").toString());
                if (states.isEmpty() || !states.getFirst().equals(payload.get("_requiredReservationStatus"))) {
                    jdbc.update("UPDATE payment_receipt_emails SET delivery_status='CANCELLED',last_error='RESOURCE_STATE_CHANGED',locked_until=NULL,lock_token=NULL WHERE id=?", id);
                    return null;
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            jdbc.update("UPDATE payment_receipt_emails SET delivery_status='FAILED',last_error='INVALID_PAYLOAD',locked_until=NULL,lock_token=NULL WHERE id=?", id);
            return null;
        }
        jdbc.update("UPDATE payment_receipt_emails SET locked_until=?,lock_token=? WHERE id=?", Timestamp.from(now.plusSeconds(120)), entry.token(), id);
        return entry;
    }

    private SecretKeySpec authMailKey() throws GeneralSecurityException {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(
            ("storagehub-auth-mail-v1\0" + jwtProperties.getSecret()).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private String protect(String body) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, authMailKey(), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return PROTECTED_PAYLOAD + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
    }

    private String unprotect(String body) throws GeneralSecurityException {
        if (!body.startsWith(PROTECTED_PAYLOAD)) return body;
        ByteBuffer encrypted = ByteBuffer.wrap(Base64.getDecoder().decode(body.substring(PROTECTED_PAYLOAD.length())));
        byte[] nonce = new byte[12];
        encrypted.get(nonce);
        byte[] content = new byte[encrypted.remaining()];
        encrypted.get(content);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, authMailKey(), new GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(content), StandardCharsets.UTF_8);
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private record Entry(long id, String userId, String recipient, String payload, int attempts,
                         String template, String subject, String auditAction, Instant expiresAt, String token) {}
}
