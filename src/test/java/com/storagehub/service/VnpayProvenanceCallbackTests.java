package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Signed test callbacks only: no external gateway or production credentials. */
class VnpayProvenanceCallbackTests {
    final VnpayProperties config = new VnpayProperties();
    final PaymentRepository payments = mock(PaymentRepository.class);
    final ReservationRepository reservations = mock(ReservationRepository.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final NotificationService notifications = mock(NotificationService.class);
    final VnpayService service = new VnpayService(config, payments, mock(PaymentComplaintRepository.class),
        reservations, mock(ReservationPricingSnapshotRepository.class), mock(UserRepository.class),
        mock(AdminAuthorizationService.class), audit, notifications, new ObjectMapper());
    <T extends BaseEntity> T id(T entity) { ReflectionTestUtils.setField(entity, "id", UUID.randomUUID()); return entity; }
    Payment fixture() {
        config.setEnabled(true); config.setTmnCode("TEST_MERCHANT"); config.setHashSecret("test-only-signature-secret");
        var p = id(new Payment()); var r = id(new Reservation());
        r.setCustomer(id(new User())); r.setFacility(id(new Facility())); r.setStatus(ReservationStatus.AWAITING_PAYMENT);
        r.setPaymentExpiresAt(Instant.parse("2026-10-11T02:00:00Z"));
        p.setReservation(r); p.setAmount(new BigDecimal("100")); p.setCurrency("VND");
        p.setPurpose(PaymentType.RESERVATION_DEPOSIT); p.setStatus(PaymentStatus.PENDING);
        p.setGatewayProvider("VNPAY"); p.setGatewayIntentId("intent");
        when(payments.findByGatewayIntentIdForUpdate("intent")).thenReturn(Optional.of(p));
        return p;
    }
    Map<String, String> callback(Map<String, String> changes) throws Exception {
        var q = new TreeMap<String, String>();
        q.put("vnp_TxnRef", "intent"); q.put("vnp_Amount", "10000"); q.put("vnp_TmnCode", config.getTmnCode());
        q.put("vnp_ResponseCode", "00"); q.put("vnp_TransactionStatus", "00");
        q.put("vnp_TransactionNo", "sandbox-transaction"); q.put("vnp_PayDate", "20261011080000"); q.putAll(changes);
        var payload = q.entrySet().stream().filter(e -> !e.getValue().isBlank())
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "&" + b).orElseThrow();
        var mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(config.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        q.put("vnp_SecureHash", HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        return q;
    }
    void noProof() {
        verify(audit, never()).recordMutation(isNull(), eq(PaymentProvenance.CALLBACK_ACTION), eq("Payment"),
            any(), any(), isNull(), any());
    }
    @Test void duplicateAuthenticatedCallbackWritesOneProofAndKeepsDevelopReplayPersistence() throws Exception {
        var p = fixture(); var q = callback(Map.of());
        assertThat(service.processCallback(q).success()).isTrue();
        assertThat(service.processCallback(q).rspCode()).isEqualTo("02");
        verify(payments, times(2)).saveAndFlush(p);
        verify(audit, times(1)).recordMutation(isNull(), eq(PaymentProvenance.CALLBACK_ACTION), eq("Payment"),
            eq(p.getId()), eq(p.getReservation().getFacility().getId()), isNull(), argThat(value -> {
                var proof = (Map<?, ?>) value;
                return "SANDBOX".equals(proof.get("environment")) && Boolean.TRUE.equals(proof.get("bookingPaymentAccepted"))
                    && "2026-10-11T01:00:00Z".equals(proof.get("gatewayPaidAt"))
                    && "2026-10-11T02:00:00Z".equals(proof.get("paymentDeadline"));
            }));
        assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(p.getPaidAt()).isEqualTo(Instant.parse("2026-10-11T01:00:00Z"));
        verify(notifications, times(1)).createNotification(any(), any(), any(), any(), any());
    }
    @Test void invalidSignatureOrAmountCreatesNoPaymentProof() throws Exception {
        fixture(); var q = callback(Map.of()); q.put("vnp_SecureHash", "invalid");
        assertThat(service.processCallback(q).rspCode()).isEqualTo("97");
        assertThat(service.processCallback(callback(Map.of("vnp_Amount", "10001"))).rspCode()).isEqualTo("04");
        verify(payments, never()).saveAndFlush(any()); verifyNoInteractions(audit);
    }
    @Test void invalidPaymentDateIsNotAcceptedAndCannotProduceProof() throws Exception {
        var p = fixture();
        for (String date : List.of("", "invalid", "20260230080000")) {
            var result = service.processCallback(callback(Map.of("vnp_PayDate", date)));
            assertThat(result.rspCode()).isEqualTo("04"); assertThat(result.success()).isFalse();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT); noProof();
    }
    @Test void lateMoneyIsRecordedForReconciliationButNeverProducesBookingProofOrCredit() throws Exception {
        var p = fixture(); var q = callback(Map.of("vnp_PayDate", "20261011100000"));
        var result = service.processCallback(q);
        assertThat(result.rspCode()).isEqualTo("00"); assertThat(result.success()).isFalse();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.getFailureCode()).isEqualTo("PAYMENT_AFTER_DEADLINE");
        assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT); noProof();
        assertThat(service.processCallback(q).rspCode()).isEqualTo("02"); noProof();
    }
    @Test void lateSuccessExpiresGraceWithoutConfirmingReservation() throws Exception {
        var p = fixture(); p.getReservation().setStatus(ReservationStatus.PAYMENT_GRACE);
        var result = service.processCallback(callback(Map.of("vnp_PayDate", "20261011100000")));
        assertThat(result.success()).isFalse(); assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.EXPIRED); noProof();
    }
    @Test void exactDeadlinePaymentMayConfirmBookingAndProduceProof() throws Exception {
        var p = fixture();
        assertThat(service.processCallback(callback(Map.of("vnp_PayDate", "20261011090000"))).success()).isTrue();
        assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }
    @Test void successfulGatewayTransactionOnExpiredReservationIsNotAcceptedBookingEvidence() throws Exception {
        var p = fixture(); p.getReservation().setStatus(ReservationStatus.EXPIRED);
        var result = service.processCallback(callback(Map.of()));
        assertThat(result.rspCode()).isEqualTo("00"); assertThat(result.success()).isFalse();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.EXPIRED); noProof();
    }
    @Test void failedCallbackNotifiesOnceAndNeverProducesProof() throws Exception {
        var p = fixture(); var q = callback(Map.of("vnp_ResponseCode", "24", "vnp_TransactionStatus", "02"));
        assertThat(service.processCallback(q).success()).isFalse(); service.processCallback(q);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(notifications, times(1)).createNotification(any(), any(), any(), any(), any()); noProof();
    }
    @Test void mismatchedMerchantDoesNotCertifyCredit() throws Exception {
        fixture(); service.processCallback(callback(Map.of("vnp_TmnCode", "OTHER_MERCHANT"))); noProof();
    }
    @Test void missingTransactionNumberDoesNotCertifyCredit() throws Exception {
        fixture(); service.processCallback(callback(Map.of("vnp_TransactionNo", ""))); noProof();
    }
    @Test void storedDateOrTransactionCannotSubstituteForSignedCallbackFields() throws Exception {
        var p = fixture(); p.setGatewayPayDate("20261011080000"); p.setGatewayTransactionNo("previous-attempt");
        service.processCallback(callback(Map.of("vnp_PayDate", "", "vnp_TransactionNo", ""))); noProof();
    }
}
