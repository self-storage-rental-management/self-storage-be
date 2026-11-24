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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Signed test callbacks only: no external gateway or production credentials. */
class VnpayProvenanceCallbackTests {
    final VnpayProperties config = new VnpayProperties();
    final PaymentRepository payments = mock(PaymentRepository.class);
    final ReservationRepository reservations = mock(ReservationRepository.class);
    final ReservationPricingSnapshotRepository snapshots = mock(ReservationPricingSnapshotRepository.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final NotificationService notifications = mock(NotificationService.class);
    final VnpayService service = new VnpayService(config, payments, mock(PaymentComplaintRepository.class),
        reservations, snapshots, mock(UserRepository.class),
        mock(AdminAuthorizationService.class), audit, notifications, new ObjectMapper());
    <T extends BaseEntity> T id(T entity) { ReflectionTestUtils.setField(entity, "id", UUID.randomUUID()); return entity; }
    Payment fixture() {
        ReflectionTestUtils.setField(service, "receiptCodes", org.mockito.Mockito.mock(MoneyReceiptCodes.class));
        config.setEnabled(true); config.setTmnCode("TEST_MERCHANT"); config.setHashSecret("test-only-signature-secret");
        var p = id(new Payment()); var r = id(new Reservation());
        r.setCustomer(id(new User())); r.setFacility(id(new Facility())); r.setStatus(ReservationStatus.AWAITING_PAYMENT);
        r.setPaymentExpiresAt(Instant.parse("2026-10-11T02:00:00Z"));
        p.setReservation(r); p.setAmount(new BigDecimal("100")); p.setCurrency("VND");
        p.setPurpose(PaymentType.RESERVATION_DEPOSIT); p.setStatus(PaymentStatus.PENDING);
        p.setGatewayProvider("VNPAY"); p.setGatewayIntentId("intent");
        var snapshot = new ReservationPricingSnapshot();
        snapshot.setRentalMonths(1);
        when(snapshots.findByReservation_Id(r.getId())).thenReturn(Optional.of(snapshot));
        when(payments.findByGatewayIntentIdForUpdate("intent")).thenReturn(Optional.of(p));
        return p;
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void transactionReceiptPreservesReservationAndRenewalPaymentMetadata(boolean renewal) {
        var payment = fixture();
        var initiator = id(new User()); initiator.setFullName("Test Customer");
        payment.setInitiatedBy(initiator);
        payment.setGatewayBankCode("TEST_BANK");
        payment.setPaidAt(Instant.parse("2026-10-11T01:00:00Z"));
        var reservationId = payment.getReservation().getId();
        if (renewal) {
            payment.setReservation(null);
            payment.setRenewal(id(new Renewal()));
            payment.setPurpose(PaymentType.RENEWAL);
        }
        com.storagehub.api.payment.VnpayTransactionResponse result =
            ReflectionTestUtils.invokeMethod(service, "response", payment, "Test receipt");
        assertThat(result.reservationId()).isEqualTo(renewal ? null : reservationId);
        assertThat(result.reservationStatus()).isEqualTo(renewal ? null : ReservationStatus.AWAITING_PAYMENT);
        assertThat(result.purpose()).isEqualTo(payment.getPurpose());
        assertThat(result.paymentMethod()).isEqualTo("TEST_BANK");
        assertThat(result.paidAt()).isEqualTo(payment.getPaidAt());
        assertThat(result.initiatedByName()).isEqualTo("Test Customer");
    }
    @Test void transactionReceiptPreservesUnknownInitiatorAndGatewayMethodFallback() {
        var payment = fixture(); payment.setInitiatedBy(null); payment.setGatewayBankCode(" ");
        com.storagehub.api.payment.VnpayTransactionResponse result =
            ReflectionTestUtils.invokeMethod(service, "response", payment, "Test receipt");
        assertThat(result.initiatedByName()).isNull();
        assertThat(result.paymentMethod()).isEqualTo("VNPAY");
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
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"deposit", "closed", "late", "renewal", "late-renewal"})
    void failedReceiptCanBeReplayedAfterRollback(String scenario) throws Exception {
        var payment = fixture();
        var reservation = payment.getReservation();
        if (scenario.equals("closed")) reservation.setStatus(ReservationStatus.EXPIRED);
        if (scenario.contains("renewal")) {
            var rental = id(new Rental());
            rental.setFacility(reservation.getFacility());
            rental.setCustomer(reservation.getCustomer());
            var renewal = id(new Renewal());
            renewal.setRental(rental);
            payment.setRenewal(renewal);
            payment.setPurpose(PaymentType.RENEWAL);
            payment.setPaymentDeadline(reservation.getPaymentExpiresAt());
            payment.setReservation(null);
        }
        var receipts = mock(MoneyReceiptCodes.class);
        ReflectionTestUtils.setField(service, "receiptCodes", receipts);
        when(receipts.issue("PAYMENT", payment.getId(), false))
            .thenThrow(scenario.equals("closed") ? new IllegalStateException("receipt collisions")
                : new org.springframework.dao.DataAccessResourceFailureException("receipt storage unavailable"))
            .thenReturn("PT-1234-5678-ABCD");
        var signed = callback(scenario.contains("late") ? Map.of("vnp_PayDate", "20261011100000") : Map.of());
        assertThatThrownBy(() -> service.processCallback(signed))
            .isInstanceOf(VnpayService.ReceiptProcessingException.class);
        // The second transaction reloads the persisted row after the first rolled back.
        payment.setStatus(PaymentStatus.PENDING);
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        var deferred = service.deferReceipt(payment.getId());
        assertThat(deferred.requiresReconciliation()).isTrue();
        assertThat(deferred.renewalId()).isEqualTo(payment.getRenewal() == null ? null : payment.getRenewal().getId());
        assertThat(deferred.reservationId()).isEqualTo(payment.getReservation() == null ? null : reservation.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        service.processCallback(signed);
        var replay = service.processCallback(signed);
        assertThat(replay.requiresReconciliation()).isEqualTo(!scenario.equals("deposit"));
        assertThat(service.deferReceipt(payment.getId()).requiresReconciliation()).isEqualTo(replay.requiresReconciliation());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getReceiptCode()).isEqualTo("PT-1234-5678-ABCD");
        verify(receipts, times(2)).issue("PAYMENT", payment.getId(), false);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void verifiedRenewalCallbackSendsOneReceiptAndIdentifiesDeposits(boolean deposit) throws Exception {
        var payment = fixture();
        var booking = payment.getReservation();
        var customer = booking.getCustomer();
        customer.setEmail("customer@example.test");
        var rental = id(new Rental());
        rental.setFacility(booking.getFacility());
        rental.setCustomer(customer);
        var renewal = id(new Renewal());
        renewal.setRental(rental);
        var quote = mock(com.storagehub.service.renewal.persistence.RenewalQuote.class);
        when(quote.getTermsJson()).thenReturn(deposit ? "{\"renewalDepositAmount\":100}" : "{\"renewalDepositAmount\":0}");
        payment.setRenewalRevision(id(new com.storagehub.service.renewal.persistence.RenewalAcceptedRevision(
            renewal, quote, 1, Instant.now(), null)));
        payment.setPaymentDeadline(booking.getPaymentExpiresAt());
        payment.setReservation(null);
        payment.setRenewal(renewal);
        payment.setPurpose(PaymentType.RENEWAL);
        when(audit.recordMutation(isNull(), eq(PaymentProvenance.CALLBACK_ACTION), eq("Payment"), any(),
            any(), isNull(), any())).thenReturn(id(new ActivityLog()));
        var emails = mock(com.storagehub.service.email.TransactionalEmailService.class);
        ReflectionTestUtils.setField(service, "transactionalEmailService", emails);

        var signed = callback(Map.of());
        service.processCallback(signed);
        service.processCallback(signed);

        verify(notifications, times(deposit ? 1 : 0)).notifyRenewalDepositReceived(customer.getId(), renewal.getId(), "100 đ");
        verify(emails).sendPaymentBillAsync(eq(customer.getId()), eq(customer.getEmail()), anyString(), eq(payment.getId()),
            eq("sandbox-transaction"), eq("100 đ"), anyString(), anyString(), nullable(String.class), anyString(),
            isNull(), eq(deposit ? "Tiền cọc gia hạn qua VNPay" : "Thanh toán gia hạn hợp đồng"), anyString());
        verifyNoMoreInteractions(emails, notifications);
    }
    static java.util.stream.Stream<Map<String, String>> conflictingReplays() {
        return java.util.stream.Stream.of(
            Map.of("vnp_TransactionNo", "another-transaction"),
            Map.of("vnp_TransactionStatus", "02"),
            Map.of("vnp_ResponseCode", "24"),
            Map.of("vnp_PayDate", "20261011100000"),
            Map.of("vnp_BankCode", "OTHER_BANK"),
            Map.of("vnp_CardType", "OTHER_CARD"));
    }
    List<Object> acceptedMetadata(Payment p) {
        return Arrays.asList(p.getGatewayIntentId(), p.getGatewayResponseCode(), p.getGatewayTransactionStatus(),
            p.getGatewayTransactionNo(), p.getGatewayPayDate(), p.getGatewayBankCode(), p.getGatewayCardType(),
            p.getPaidAt(), p.getProcessedAt(), p.getFailureCode(), p.getFailureReason(),
            p.getStatus(), p.getReservation().getStatus());
    }
    @ParameterizedTest @MethodSource("conflictingReplays")
    void conflictingSignedReplayCannotRewriteAcceptedTransaction(Map<String, String> changes) throws Exception {
        var p = fixture();
        var q = callback(Map.of("vnp_BankCode", "TEST_BANK", "vnp_CardType", "ATM"));
        service.processCallback(q); var before = acceptedMetadata(p);
        var result = service.processCallback(callback(changes));
        assertThat(result.rspCode()).isEqualTo("02");
        assertThat(result.message()).contains("conflicting callback ignored");
        assertThat(result.success()).isTrue(); // Preserve the owner's already-PAID response semantics.
        assertThat(acceptedMetadata(p)).isEqualTo(before);
        verify(audit, times(1)).recordMutation(isNull(), eq(PaymentProvenance.CALLBACK_ACTION), eq("Payment"),
            eq(p.getId()), any(), isNull(), any());
        verify(notifications, times(1)).createNotification(any(), any(), any(), any(), any());
    }
    @ParameterizedTest @MethodSource("conflictingReplays")
    void conflictingReplayCannotRewriteLateReconciliationFunds(Map<String, String> changes) throws Exception {
        var p = fixture(); p.getReservation().setStatus(ReservationStatus.PAYMENT_GRACE);
        service.processCallback(callback(Map.of("vnp_PayDate", "20261011100000")));
        var before = acceptedMetadata(p); clearInvocations(notifications);
        assertThat(service.processCallback(callback(changes)).rspCode()).isEqualTo("02");
        assertThat(acceptedMetadata(p)).isEqualTo(before);
        assertThat(p.getFailureCode()).isEqualTo("PAYMENT_AFTER_DEADLINE");
        assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        noProof(); verifyNoInteractions(notifications);
    }
    @Test void invalidChecksumReplayCannotTouchAcceptedMetadata() throws Exception {
        var p = fixture(); service.processCallback(callback(Map.of()));
        var before = acceptedMetadata(p); clearInvocations(payments, audit, notifications);
        var q = callback(Map.of("vnp_TransactionNo", "tampered")); q.put("vnp_SecureHash", "invalid");
        assertThat(service.processCallback(q).rspCode()).isEqualTo("97");
        assertThat(acceptedMetadata(p)).isEqualTo(before);
        verifyNoInteractions(payments, audit, notifications);
    }
    @Test void replayCannotEnrichMissingAcceptedOptionalMetadata() throws Exception {
        var p = fixture(); service.processCallback(callback(Map.of()));
        var before = acceptedMetadata(p);
        service.processCallback(callback(Map.of("vnp_BankCode", "NEW_BANK", "vnp_CardType", "ATM")));
        assertThat(acceptedMetadata(p)).isEqualTo(before);
    }
    @Test void duplicateAuthenticatedCallbackWritesOneProofAndKeepsDevelopReplayPersistence() throws Exception {
        var p = fixture(); var q = callback(Map.of());
        assertThat(service.processCallback(q).success()).isTrue();
        assertThat(service.processCallback(q).rspCode()).isEqualTo("02");
        assertThat(service.processCallback(q).message()).isEqualTo("Order already confirmed");
        verify(payments, times(3)).saveAndFlush(p);
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
    @Test void mismatchedMerchantCannotChangeReservationDepositState() throws Exception {
        var payment=fixture();var result=service.processCallback(callback(Map.of("vnp_TmnCode", "OTHER_MERCHANT")));
        assertThat(result.rspCode()).isEqualTo("97");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getReservation().getStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        verify(payments,never()).saveAndFlush(any());noProof();
    }
    @Test void futureGatewayPaymentDateCannotConfirmReservationDeposit() throws Exception {
        var payment=fixture();var result=service.processCallback(callback(Map.of("vnp_PayDate","20990101080000")));
        assertThat(result.rspCode()).isEqualTo("04");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getReservation().getStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);noProof();
    }
    @Test void missingTransactionNumberDoesNotCertifyCredit() throws Exception {
        fixture(); service.processCallback(callback(Map.of("vnp_TransactionNo", ""))); noProof();
    }
    @Test void storedDateOrTransactionCannotSubstituteForSignedCallbackFields() throws Exception {
        var p = fixture(); p.setGatewayPayDate("20261011080000"); p.setGatewayTransactionNo("previous-attempt");
        service.processCallback(callback(Map.of("vnp_PayDate", "", "vnp_TransactionNo", ""))); noProof();
    }
}
