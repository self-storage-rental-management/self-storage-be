package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.payment.VnpayPaymentUrlResponse;
import com.storagehub.api.payment.VnpayRefundRequest;
import com.storagehub.api.payment.VnpayTransactionResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VnpayService {

    private static final String VERSION = "2.1.0";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE);

    private final VnpayProperties properties;
    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Transactional
    public VnpayPaymentUrlResponse createPaymentUrl(
        ActorPrincipal actor,
        UUID reservationId,
        String idempotencyKey,
        String clientIp
    ) {
        requireConfigured();
        String key = requiredKey(idempotencyKey);
        Payment existing = paymentRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            if (!existing.getInitiatedBy().getId().equals(actor.userId())
                || !existing.getReservation().getId().equals(reservationId)) {
                throw ApiExceptions.conflict("Idempotency-Key is already used for another payment");
            }
            return paymentUrl(existing, clientIp);
        }

        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        requirePayable(reservation);
        Payment alreadyPaid = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservationId, PaymentType.RESERVATION_DEPOSIT)
            .filter(value -> value.getStatus() == PaymentStatus.PAID)
            .orElse(null);
        if (alreadyPaid != null) {
            throw ApiExceptions.conflict("Reservation deposit is already paid");
        }

        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        User initiator = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Payment payment = new Payment();
        payment.setInitiatedBy(initiator);
        payment.setReservation(reservation);
        payment.setAmount(snapshot.getReservationDepositAmount());
        payment.setCurrency("VND");
        payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setIdempotencyKey(key);
        payment.setGatewayIntentId("SH" + UUID.randomUUID().toString().replace("-", ""));
        payment.setGatewayProvider("VNPAY");
        Payment saved = paymentRepository.saveAndFlush(payment);
        auditLogService.recordMutation(
            initiator, "VNPAY_PAYMENT_CREATED", "Payment", saved.getId(),
            reservation.getFacility().getId(), null,
            Map.of("gateway", "VNPAY", "transactionReference", saved.getGatewayIntentId(), "amount", saved.getAmount())
        );
        return paymentUrl(saved, clientIp);
    }

    @Transactional
    public CallbackResult processCallback(Map<String, String> query) {
        requireConfigured();
        if (!verify(query)) {
            return new CallbackResult("97", "Invalid checksum", null, null, false);
        }
        String reference = query.get("vnp_TxnRef");
        Payment payment = paymentRepository.findByGatewayIntentIdForUpdate(reference).orElse(null);
        if (payment == null) {
            return new CallbackResult("01", "Order not found", null, null, false);
        }
        long receivedAmount;
        try {
            receivedAmount = Long.parseLong(query.getOrDefault("vnp_Amount", "-1"));
        } catch (NumberFormatException exception) {
            return new CallbackResult("04", "Invalid amount", payment.getId(), payment.getReservation().getId(), false);
        }
        if (receivedAmount != amountMinor(payment.getAmount())) {
            return new CallbackResult("04", "Invalid amount", payment.getId(), payment.getReservation().getId(), false);
        }
        if (payment.getStatus() == PaymentStatus.PAID) {
            return new CallbackResult("02", "Order already confirmed", payment.getId(), payment.getReservation().getId(), true);
        }

        String responseCode = query.getOrDefault("vnp_ResponseCode", "99");
        String transactionStatus = query.getOrDefault("vnp_TransactionStatus", responseCode);
        payment.setGatewayResponseCode(responseCode);
        payment.setGatewayTransactionStatus(transactionStatus);
        payment.setGatewayTransactionNo(query.get("vnp_TransactionNo"));
        payment.setGatewayBankCode(query.get("vnp_BankCode"));
        payment.setGatewayCardType(query.get("vnp_CardType"));
        payment.setGatewayPayDate(query.get("vnp_PayDate"));
        payment.setProcessedAt(Instant.now());
        boolean success = "00".equals(responseCode) && "00".equals(transactionStatus);
        Reservation reservation = payment.getReservation();
        if (success) {
            markPaid(payment);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode(responseCode);
            payment.setFailureReason("VNPAY transaction was not successful");
        }
        paymentRepository.saveAndFlush(payment);
        return new CallbackResult("00", "Confirm Success", payment.getId(), reservation.getId(), success);
    }

    @Transactional
    public VnpayTransactionResponse query(ActorPrincipal actor, UUID paymentId, String clientIp) {
        requireConfigured();
        authorizationService.require(actor, SystemPermission.VIEW_PAYMENTS);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment was not found"));
        Map<String, String> request = queryRequest(payment, clientIp);
        Map<String, String> response = post(request);
        payment.setLastReconciledAt(Instant.now());
        payment.setGatewayResponseCode(response.get("vnp_ResponseCode"));
        payment.setGatewayTransactionStatus(response.get("vnp_TransactionStatus"));
        if (response.get("vnp_TransactionNo") != null) payment.setGatewayTransactionNo(response.get("vnp_TransactionNo"));
        if (response.get("vnp_PayDate") != null) payment.setGatewayPayDate(response.get("vnp_PayDate"));
        if ("00".equals(response.get("vnp_ResponseCode"))
            && "00".equals(response.get("vnp_TransactionStatus"))
            && payment.getStatus() != PaymentStatus.PAID) {
            markPaid(payment);
        }
        paymentRepository.saveAndFlush(payment);
        return response(payment, response.getOrDefault("vnp_Message", "Query completed"));
    }

    @Transactional
    public VnpayTransactionResponse refund(
        ActorPrincipal actor,
        UUID paymentId,
        VnpayRefundRequest request,
        String clientIp
    ) {
        requireConfigured();
        authorizationService.require(actor, SystemPermission.MANAGE_PAYMENTS);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment was not found"));
        if (payment.getStatus() != PaymentStatus.PAID || payment.getGatewayTransactionNo() == null) {
            throw ApiExceptions.conflict("Only a successful VNPAY payment can be refunded");
        }
        BigDecimal remaining = payment.getAmount().subtract(payment.getRefundedAmount());
        if (request.amount().compareTo(remaining) > 0) {
            throw ApiExceptions.validation("Refund amount exceeds the refundable amount", null);
        }
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Map<String, String> payload = refundRequest(payment, request, user.getEmail(), clientIp);
        Map<String, String> gateway = post(payload);
        if ("00".equals(gateway.get("vnp_ResponseCode"))) {
            payment.setRefundedAmount(payment.getRefundedAmount().add(request.amount()));
        }
        payment.setLastReconciledAt(Instant.now());
        paymentRepository.saveAndFlush(payment);
        auditLogService.recordMutation(
            user, "VNPAY_REFUND_REQUESTED", "Payment", payment.getId(),
            payment.getReservation().getFacility().getId(), null,
            Map.of("amount", request.amount(), "reason", request.reason(),
                "responseCode", gateway.getOrDefault("vnp_ResponseCode", "99"))
        );
        return response(payment, gateway.getOrDefault("vnp_Message", "Refund request completed"));
    }

    @Transactional(readOnly = true)
    public VnpayTransactionResponse get(ActorPrincipal actor, UUID reservationId) {
        Payment payment = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservationId, PaymentType.RESERVATION_DEPOSIT)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation payment was not found"));
        if (!payment.getReservation().getCustomer().getId().equals(actor.userId())) {
            throw ApiExceptions.notFound("Reservation payment was not found");
        }
        return response(payment, message(payment));
    }

    private VnpayPaymentUrlResponse paymentUrl(Payment payment, String clientIp) {
        Instant now = Instant.now();
        Map<String, String> values = new TreeMap<>();
        values.put("vnp_Version", VERSION);
        values.put("vnp_Command", "pay");
        values.put("vnp_TmnCode", properties.getTmnCode());
        values.put("vnp_Amount", String.valueOf(amountMinor(payment.getAmount())));
        values.put("vnp_CurrCode", "VND");
        values.put("vnp_TxnRef", payment.getGatewayIntentId());
        values.put("vnp_OrderInfo", "Thanh toan coc don " + payment.getReservation().getReservationCode());
        values.put("vnp_OrderType", "other");
        values.put("vnp_Locale", "vn");
        values.put("vnp_ReturnUrl", properties.getReturnUrl());
        values.put("vnp_IpAddr", ip(clientIp));
        values.put("vnp_CreateDate", DATE_TIME.format(now));
        values.put("vnp_ExpireDate", DATE_TIME.format(now.plus(Duration.ofMinutes(15))));
        String query = encode(values);
        String url = properties.getPayUrl() + "?" + query + "&vnp_SecureHash=" + hmac(query);
        return new VnpayPaymentUrlResponse(
            payment.getId(), payment.getReservation().getId(), payment.getGatewayIntentId(),
            payment.getAmount(), payment.getCurrency(), url
        );
    }

    private Map<String, String> queryRequest(Payment payment, String clientIp) {
        String createDate = DATE_TIME.format(Instant.now());
        String transactionDate = payment.getGatewayPayDate() != null
            ? payment.getGatewayPayDate() : DATE_TIME.format(payment.getCreatedAt());
        Map<String, String> values = new HashMap<>();
        values.put("vnp_RequestId", requestId()); values.put("vnp_Version", VERSION);
        values.put("vnp_Command", "querydr"); values.put("vnp_TmnCode", properties.getTmnCode());
        values.put("vnp_TxnRef", payment.getGatewayIntentId()); values.put("vnp_TransactionDate", transactionDate);
        values.put("vnp_CreateDate", createDate); values.put("vnp_IpAddr", ip(clientIp));
        values.put("vnp_OrderInfo", "Doi soat " + payment.getGatewayIntentId());
        values.put("vnp_SecureHash", hmac(join(values, "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TxnRef", "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo")));
        return values;
    }

    private Map<String, String> refundRequest(Payment payment, VnpayRefundRequest request, String createBy, String clientIp) {
        Map<String, String> values = new HashMap<>();
        values.put("vnp_RequestId", requestId()); values.put("vnp_Version", VERSION);
        values.put("vnp_Command", "refund"); values.put("vnp_TmnCode", properties.getTmnCode());
        values.put("vnp_TransactionType", request.amount().compareTo(payment.getAmount()) == 0 ? "02" : "03");
        values.put("vnp_TxnRef", payment.getGatewayIntentId());
        values.put("vnp_Amount", String.valueOf(amountMinor(request.amount())));
        values.put("vnp_TransactionNo", payment.getGatewayTransactionNo());
        values.put("vnp_TransactionDate", payment.getGatewayPayDate());
        values.put("vnp_CreateBy", createBy.substring(0, Math.min(createBy.length(), 32)));
        values.put("vnp_CreateDate", DATE_TIME.format(Instant.now())); values.put("vnp_IpAddr", ip(clientIp));
        values.put("vnp_OrderInfo", request.reason());
        values.put("vnp_SecureHash", hmac(join(values, "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TransactionType", "vnp_TxnRef", "vnp_Amount", "vnp_TransactionNo", "vnp_TransactionDate", "vnp_CreateBy", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo")));
        return values;
    }

    private Map<String, String> post(Map<String, String> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiUrl()))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiExceptions.conflict("VNPAY API is unavailable");
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiExceptions.conflict("VNPAY API request was interrupted");
        } catch (java.io.IOException exception) {
            throw ApiExceptions.conflict("VNPAY API is unavailable");
        }
    }

    private boolean verify(Map<String, String> query) {
        String supplied = query.get("vnp_SecureHash");
        if (supplied == null) return false;
        Map<String, String> signed = new TreeMap<>();
        query.forEach((key, value) -> {
            if (key.startsWith("vnp_") && !"vnp_SecureHash".equals(key) && !"vnp_SecureHashType".equals(key)
                && value != null && !value.isBlank()) signed.put(key, value);
        });
        return constantTimeEquals(hmac(encode(signed)), supplied);
    }

    private String encode(Map<String, String> values) {
        return values.entrySet().stream().filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
            .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
            .reduce((left, right) -> left + "&" + right).orElse("");
    }

    private String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String join(Map<String, String> values, String... keys) {
        return java.util.Arrays.stream(keys).map(key -> values.getOrDefault(key, "")).reduce((a, b) -> a + "|" + b).orElse("");
    }
    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(properties.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA512 is unavailable", exception);
        }
    }
    private boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(expected.toLowerCase().getBytes(StandardCharsets.US_ASCII), actual.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
    private long amountMinor(BigDecimal amount) { return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.UNNECESSARY).longValueExact(); }
    private String requestId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 32); }
    private String ip(String value) { return value == null || value.isBlank() ? "127.0.0.1" : value.split(",")[0].trim(); }
    private String requiredKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) throw ApiExceptions.validation("Idempotency-Key is required and must be at most 100 characters", null);
        return value.trim();
    }
    private void requireConfigured() {
        if (!properties.isEnabled() || properties.getTmnCode().isBlank() || properties.getHashSecret().isBlank()) {
            throw ApiExceptions.conflict("VNPAY is not configured");
        }
    }
    private void requirePayable(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.AWAITING_PAYMENT) throw ApiExceptions.conflict("Reservation is not awaiting payment");
        Instant deadline = reservation.getPaymentExpiresAt() != null ? reservation.getPaymentExpiresAt() : reservation.getHoldExpiresAt();
        if (deadline == null || !deadline.isAfter(Instant.now())) throw ApiExceptions.conflict("Reservation payment deadline has expired");
    }
    private void markPaid(Payment payment) {
        Instant paidAt = payment.getPaidAt() != null ? payment.getPaidAt() : Instant.now();
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(paidAt);
        payment.setProcessedAt(Instant.now());
        Reservation reservation = payment.getReservation();
        if (reservation.getStatus() == ReservationStatus.AWAITING_PAYMENT
            || reservation.getStatus() == ReservationStatus.PAYMENT_GRACE
            || reservation.getStatus() == ReservationStatus.PAYMENT_REVIEW) {
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setDepositPaidAt(paidAt);
            reservation.setConfirmedAt(paidAt);
            reservationRepository.saveAndFlush(reservation);
        }
    }
    private VnpayTransactionResponse response(Payment payment, String message) {
        return new VnpayTransactionResponse(payment.getId(), payment.getReservation().getId(), payment.getGatewayIntentId(),
            payment.getGatewayTransactionNo(), payment.getAmount(), payment.getCurrency(), payment.getRefundedAmount(), payment.getStatus(),
            payment.getReservation().getStatus(), payment.getGatewayResponseCode(), payment.getGatewayTransactionStatus(),
            message, payment.getProcessedAt(), payment.getLastReconciledAt());
    }
    private String message(Payment payment) {
        return payment.getStatus() == PaymentStatus.PAID ? "Payment was recorded successfully"
            : payment.getStatus() == PaymentStatus.FAILED ? "Payment was not successful" : "Payment is awaiting VNPAY confirmation";
    }

    public record CallbackResult(String rspCode, String message, UUID paymentId, UUID reservationId, boolean success) {}
}
