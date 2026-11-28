package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.repo.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class VnpayQueryDrTests {
    final ObjectMapper mapper = new ObjectMapper();
    final VnpayProperties config = new VnpayProperties();
    final VnpayService service = new VnpayService(config, mock(PaymentRepository.class),
        mock(PaymentComplaintRepository.class), mock(ReservationRepository.class),
        mock(ReservationPricingSnapshotRepository.class), mock(UserRepository.class),
        mock(AdminAuthorizationService.class), mock(AuditLogService.class), mock(NotificationService.class), mapper);
    final Payment payment = new Payment();
    HttpServer server;
    Map<String, String> response;
    Map<String, String> request;

    @BeforeEach void setup() throws Exception {
        config.setEnabled(true); config.setTmnCode("TESTCODE"); config.setHashSecret("test-only-secret");
        payment.setGatewayIntentId("SHtest"); payment.setAmount(new BigDecimal("12730000"));
        payment.setStatus(PaymentStatus.PENDING);
        ReflectionTestUtils.setField(payment, "createdAt", Instant.parse("2026-11-27T07:00:00Z"));
        payment.setGatewayCreateDate("20261127140001");
        response = new HashMap<>();
        response.put("vnp_ResponseId", "response1"); response.put("vnp_Command", "querydr");
        response.put("vnp_ResponseCode", "00"); response.put("vnp_Message", "Success");
        response.put("vnp_TmnCode", "TESTCODE"); response.put("vnp_TxnRef", "SHtest");
        response.put("vnp_Amount", "1273000000"); response.put("vnp_BankCode", "NCB");
        response.put("vnp_PayDate", "20261127141000"); response.put("vnp_TransactionNo", "123456");
        response.put("vnp_TransactionType", "01"); response.put("vnp_TransactionStatus", "00");
        response.put("vnp_OrderInfo", "Test payment");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            request = mapper.readValue(exchange.getRequestBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {});
            byte[] body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/query");
    }
    @AfterEach void cleanup() { server.stop(0); }

    @Test void unsigned99IsUnavailableAndDoesNotOverwritePaymentEvidence() {
        payment.setGatewayTransactionNo("stored-evidence");
        response = Map.of("vnp_ResponseCode", "99", "vnp_Message", "Unknown error");
        var result = service.verifyPaymentForComplaint(payment, "::1");
        assertThat(result.conclusion()).isEqualTo("UNAVAILABLE");
        assertThat(result.responseCode()).isEqualTo("99");
        assertThat(result.message()).contains("99", "không có chữ ký");
        assertThat(payment.getGatewayTransactionNo()).isEqualTo("stored-evidence");
        assertThat(payment.getGatewayResponseCode()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
    @Test void queryUsesOriginalCreateDateAndNormalizesLoopbackBeforeSigning() throws Exception {
        payment.setGatewayPayDate("20261127141000"); signResponse();
        service.verifyPaymentForComplaint(payment, "::1");
        assertThat(request.get("vnp_TransactionDate")).isEqualTo("20261127140001");
        assertThat(request.get("vnp_IpAddr")).isEqualTo("127.0.0.1");
        assertThat(request.get("vnp_SecureHash")).isEqualTo(sign(String.join("|",
            request.get("vnp_RequestId"), "2.1.0", "querydr", "TESTCODE", "SHtest",
            "20261127140001", request.get("vnp_CreateDate"), "127.0.0.1", "Doi soat SHtest")));
    }
    @Test void legacyQueryUsesCreationTimeRatherThanPayDate() throws Exception {
        payment.setGatewayCreateDate(null); payment.setGatewayPayDate("20261127141000"); signResponse();
        service.verifyPaymentForComplaint(payment, "127.0.0.1");
        assertThat(request.get("vnp_TransactionDate")).isEqualTo("20261127140000");
    }
    @Test void paymentUrlRetainsItsOriginalCreateDateAcrossRetries() {
        payment.setGatewayCreateDate(null);
        String first = ReflectionTestUtils.invokeMethod(service, "gatewayPaymentUrl", payment,
            Instant.now().plusSeconds(900), "Test", "127.0.0.1");
        String original = payment.getGatewayCreateDate();
        assertThat(original).matches("\\d{14}");
        assertThat(first).contains("vnp_CreateDate=" + original);
        String retry = ReflectionTestUtils.invokeMethod(service, "gatewayPaymentUrl", payment,
            Instant.now().plusSeconds(900), "Test", "127.0.0.1");
        assertThat(retry).contains("vnp_CreateDate=" + original);
    }
    @Test void validSignedSuccessWithNullOptionalFieldsConfirmsPaymentEvidenceOnly() throws Exception {
        response.put("vnp_PromotionCode", null); response.put("vnp_PromotionAmount", null); signResponse();
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("PAID_CONFIRMED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
    @Test void unsignedSuccessCannotConfirmPayment() {
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("INVALID_SIGNATURE");
    }
    @Test void tamperedSignedErrorRemainsInvalidSignature() throws Exception {
        response.put("vnp_ResponseCode", "99"); signResponse(); response.put("vnp_Message", "tampered");
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("INVALID_SIGNATURE");
    }
    @Test void signedWrongReferenceCannotConfirmPayment() throws Exception {
        response.put("vnp_TxnRef", "other"); signResponse();
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("INVALID_SIGNATURE");
    }
    @Test void signedWrongMerchantCannotConfirmPayment() throws Exception {
        response.put("vnp_TmnCode", "other"); signResponse();
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("INVALID_SIGNATURE");
    }
    @Test void signedWrongAmountCannotConfirmPayment() throws Exception {
        response.put("vnp_Amount", "1"); signResponse();
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("INVALID_SIGNATURE");
    }
    @Test void signedMissingAmountCannotConfirmPayment() throws Exception {
        response.remove("vnp_Amount"); signResponse();
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("UNAVAILABLE");
    }
    @Test void signedNotFoundRetainsNotFoundConclusion() throws Exception {
        response.put("vnp_ResponseCode", "91"); signResponse();
        assertThat(service.verifyPaymentForComplaint(payment, "127.0.0.1").conclusion()).isEqualTo("NOT_FOUND");
    }
    void signResponse() throws Exception {
        var keys = List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
            "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate",
            "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo",
            "vnp_PromotionCode", "vnp_PromotionAmount");
        response.put("vnp_SecureHash", sign(String.join("|", keys.stream()
            .map(k -> Objects.toString(response.get(k), "")).toList())));
    }
    String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(config.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
