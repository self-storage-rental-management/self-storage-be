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
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VnpayProvenanceCallbackTests {
    final VnpayProperties config=new VnpayProperties();
    final PaymentRepository payments=mock(PaymentRepository.class);
    final ReservationRepository reservations=mock(ReservationRepository.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final VnpayService service=new VnpayService(config,payments,reservations,mock(ReservationPricingSnapshotRepository.class),mock(UserRepository.class),mock(AdminAuthorizationService.class),audit,new ObjectMapper());
    <T extends BaseEntity>T id(T e){ReflectionTestUtils.setField(e,"id",UUID.randomUUID());return e;}
    Payment fixture(){config.setEnabled(true);config.setTmnCode("TEST_MERCHANT");config.setHashSecret("test-only-signature-secret");var p=id(new Payment());var r=id(new Reservation());r.setCustomer(id(new User()));r.setFacility(id(new Facility()));r.setStatus(ReservationStatus.AWAITING_PAYMENT);p.setReservation(r);p.setAmount(new BigDecimal("100"));p.setCurrency("VND");p.setPurpose(PaymentType.RESERVATION_DEPOSIT);p.setGatewayProvider("VNPAY");p.setGatewayIntentId("intent");when(payments.findByGatewayIntentIdForUpdate("intent")).thenReturn(Optional.of(p));return p;}
    Map<String,String> callback(String amount)throws Exception{var q=new TreeMap<String,String>();q.put("vnp_TxnRef","intent");q.put("vnp_Amount",amount);q.put("vnp_TmnCode",config.getTmnCode());q.put("vnp_ResponseCode","00");q.put("vnp_TransactionStatus","00");q.put("vnp_TransactionNo","sandbox-transaction");var payload=q.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElseThrow();var mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec(config.getHashSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA512"));q.put("vnp_SecureHash",HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));return q;}
    @Test void duplicateAuthenticatedCallbackWritesOneProofAndOnePayment()throws Exception{var p=fixture();var q=callback("10000");assertThat(service.processCallback(q).rspCode()).isEqualTo("00");assertThat(service.processCallback(q).rspCode()).isEqualTo("02");verify(payments,times(1)).saveAndFlush(p);verify(audit,times(1)).recordMutation(isNull(),eq(PaymentProvenance.CALLBACK_ACTION),eq("Payment"),eq(p.getId()),eq(p.getReservation().getFacility().getId()),isNull(),argThat(v->"SANDBOX".equals(((Map<?,?>)v).get("environment"))));assertThat(p.getReservation().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);}
    @Test void invalidSignatureOrAmountCreatesNoPaymentProof()throws Exception{fixture();var q=callback("10000");q.put("vnp_SecureHash","invalid");assertThat(service.processCallback(q).rspCode()).isEqualTo("97");assertThat(service.processCallback(callback("10001")).rspCode()).isEqualTo("04");verify(payments,never()).saveAndFlush(any());verifyNoInteractions(audit);}
}
