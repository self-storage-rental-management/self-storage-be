package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.ledger.RenewalVnpaySettlementService;
import com.storagehub.service.renewal.RenewalWorkflowService;
import com.storagehub.service.renewal.persistence.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

class RenewalPaymentIntentTests {
    PaymentRepository payments=mock(PaymentRepository.class);UserRepository users=mock(UserRepository.class);
    ReservationRepository reservations=mock(ReservationRepository.class);
    ReservationPricingSnapshotRepository snapshots=mock(ReservationPricingSnapshotRepository.class);
    RenewalWorkflowService renewal=mock(RenewalWorkflowService.class);VnpayService gateway;
    UUID renewalId=UUID.randomUUID();User user=id(new User());Renewal n=id(new Renewal());
    VnpayProperties config=new VnpayProperties();
    RenewalAcceptedRevision revision;ActorPrincipal actor;
    Instant deadline=Instant.now().plusSeconds(3600);
    static <T extends BaseEntity>T id(T e){ReflectionTestUtils.setField(e,"id",UUID.randomUUID());return e;}
    @BeforeEach void setup() {
        config.setEnabled(true);config.setTmnCode("TEST");config.setHashSecret("test-only");
        var beans=new StaticListableBeanFactory();beans.addBean("settlement",mock(RenewalVnpaySettlementService.class));
        gateway=new VnpayService(config,payments,mock(PaymentComplaintRepository.class),reservations,
            snapshots,users,mock(AdminAuthorizationService.class),mock(AuditLogService.class),
            mock(NotificationService.class),new ObjectMapper(),renewal,beans.getBeanProvider(RenewalVnpaySettlementService.class));
        actor=new ActorPrincipal(user.getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
        ReflectionTestUtils.setField(n,"id",renewalId);var rental=id(new Rental());rental.setCustomer(user);rental.setFacility(id(new Facility()));n.setRental(rental);
        var quote=id(new RenewalQuote(rental,user,"{}","hash",Instant.now(),Instant.now().plusSeconds(60)));
        revision=id(new RenewalAcceptedRevision(n,quote,1,Instant.now(),null));
        var terms=new RenewalQuoteResponse.Terms(LocalDate.of(2026,10,31),LocalDate.of(2026,11,1),LocalDate.of(2026,12,1),LocalDate.of(2026,11,30),UUID.randomUUID(),"TEST",UUID.randomUUID(),"v1",1,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),"VND","test","v1",UUID.randomUUID(),rental.getFacility().getId());
        when(renewal.prepareVnpayPayment(actor,renewalId)).thenReturn(new RenewalWorkflowService.PreparedPayment(n,new RenewalWorkflow(n,revision),revision,terms,deadline));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));when(payments.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(payments.findAllByRenewal_IdAndStatusIn(any(),any())).thenReturn(List.of());
        when(payments.saveAndFlush(any())).thenAnswer(c->{Payment p=c.getArgument(0);if(p.getId()==null)id(p);return p;});
    }
    @Test void reservationReusesItsPendingDepositIntentWhenClientUsesANewKey() {
        var reservation=id(new Reservation());reservation.setReservationCode("TEST-RESERVATION");
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);reservation.setPaymentExpiresAt(deadline);
        var pending=id(new Payment());pending.setReservation(reservation);pending.setInitiatedBy(user);
        pending.setAmount(new BigDecimal("20"));pending.setCurrency("VND");pending.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        pending.setStatus(PaymentStatus.PENDING);pending.setGatewayIntentId("existing-reservation-intent");
        when(reservations.findOwnedByIdForUpdate(reservation.getId(),user.getId())).thenReturn(Optional.of(reservation));
        when(payments.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservation.getId(),PaymentType.RESERVATION_DEPOSIT))
            .thenReturn(Optional.of(pending));

        var response=gateway.createPaymentUrl(actor,reservation.getId(),"new-client-key","127.0.0.1");

        assertThat(response.paymentId()).isEqualTo(pending.getId());
        assertThat(response.paymentUrl()).contains("existing-reservation-intent");
        verify(payments,never()).saveAndFlush(any(Payment.class));
    }
    @Test void intentUsesAcceptedServerQuoteAndBindsFullRentRevisionDeadline() {
        var response=gateway.createRenewalPaymentUrl(actor,renewalId,"stable-key","127.0.0.1");
        var captured=org.mockito.ArgumentCaptor.forClass(Payment.class);verify(payments).saveAndFlush(captured.capture());var p=captured.getValue();
        assertThat(p.getAmount()).isEqualByComparingTo("100");assertThat(p.getCurrency()).isEqualTo("VND");assertThat(p.getPurpose()).isEqualTo(PaymentType.RENEWAL);
        assertThat(p.getReservation()).isNull();assertThat(p.getRenewalRevision()).isSameAs(revision);assertThat(p.getPaymentDeadline()).isNotNull();
        assertThat(response.paymentUrl()).contains("vnp_Amount=10000","vnp_CurrCode=VND",p.getGatewayIntentId());
    }
    @Test void renewalAndReservationUseSameMerchantGatewayReturnAndSignature() throws Exception {
        config.setTmnCode("SHARED_TEST_MERCHANT");config.setHashSecret("shared-test-only-secret");
        config.setReturnUrl("http://localhost:8080/api/public/payments/vnpay/return");
        var reservation=id(new Reservation());reservation.setReservationCode("TEST-RESERVATION");
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);reservation.setPaymentExpiresAt(deadline);
        var deposit=id(new Payment());deposit.setReservation(reservation);deposit.setInitiatedBy(user);
        deposit.setAmount(new BigDecimal("20"));deposit.setCurrency("VND");deposit.setGatewayIntentId("reservation-intent");
        when(payments.findByIdempotencyKey("reservation-key")).thenReturn(Optional.of(deposit));
        var reservationUrl=gateway.createPaymentUrl(actor,reservation.getId(),"reservation-key","127.0.0.1").paymentUrl();
        var renewalUrl=gateway.createRenewalPaymentUrl(actor,renewalId,"renewal-key","127.0.0.1").paymentUrl();
        for(String url:List.of(reservationUrl,renewalUrl)) {
            var uri=URI.create(url);assertThat(uri.getScheme()+"://"+uri.getAuthority()+uri.getPath()).isEqualTo(config.getPayUrl());
            var values=new HashMap<String,String>();
            for(String parameter:uri.getRawQuery().split("&")) {
                var pair=parameter.split("=",2);values.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
            }
            assertThat(values.get("vnp_TmnCode")).isEqualTo(config.getTmnCode());
            assertThat(values.get("vnp_ReturnUrl")).isEqualTo(config.getReturnUrl());
            assertThat(values.get("vnp_Command")).isEqualTo("pay");
            String signedQuery=uri.getRawQuery().substring(0,uri.getRawQuery().lastIndexOf("&vnp_SecureHash="));
            var mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec(config.getHashSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
            assertThat(values.get("vnp_SecureHash")).isEqualTo(HexFormat.of().formatHex(mac.doFinal(signedQuery.getBytes(StandardCharsets.UTF_8))));
            assertThat(values.get("vnp_Amount")).isEqualTo(url.equals(renewalUrl)?"10000":"2000");
        }
    }
    @Test void sameKeyCannotBeReassignedToAnotherRenewalOrAcceptedRevision() {
        var p=existing();var other=id(new Renewal());p.setRenewal(other);when(payments.findByIdempotencyKey("key")).thenReturn(Optional.of(p));
        assertThatThrownBy(()->gateway.createRenewalPaymentUrl(actor,renewalId,"key","127.0.0.1")).hasMessageContaining("another payment");
        p.setRenewal(n);p.setRenewalRevision(id(new RenewalAcceptedRevision(n,revision.getQuote(),2,Instant.now(),null)));
        assertThatThrownBy(()->gateway.createRenewalPaymentUrl(actor,renewalId,"key","127.0.0.1")).hasMessageContaining("earlier accepted revision");verify(payments,never()).saveAndFlush(any());
    }
    @Test void retryAfterLosingBrowserKeyReusesOnlyTheOwnedCurrentIntent() {
        var p=existing();when(payments.findAllByRenewal_IdAndStatusIn(any(),any())).thenReturn(List.of(p));
        assertThat(gateway.createRenewalPaymentUrl(actor,renewalId,"replacement-key","127.0.0.1").paymentId()).isEqualTo(p.getId());verify(payments,never()).saveAndFlush(any());
    }
    @Test void receivedFundsBlockNewIntentsAndPaidReplayDoesNotReopenGateway() {
        var p=existing();p.setStatus(PaymentStatus.PAID);when(payments.findAllByRenewal_IdAndStatusIn(any(),any())).thenReturn(List.of(p));
        assertThatThrownBy(()->gateway.createRenewalPaymentUrl(actor,renewalId,"new-key","127.0.0.1")).hasMessageContaining("already been received");
        when(payments.findByIdempotencyKey("key")).thenReturn(Optional.of(p));assertThat(gateway.createRenewalPaymentUrl(actor,renewalId,"key","127.0.0.1").paymentUrl()).isNull();
    }
    @Test void storedIntentCannotOverrideAcceptedAmountCurrencyPurposeOrDeadline() {
        for(String field:List.of("amount","currency","purpose","deadline")) {
            var p=existing();switch(field){case "amount"->p.setAmount(BigDecimal.ONE);case "currency"->p.setCurrency("USD");case "purpose"->p.setPurpose(PaymentType.OTHER);case "deadline"->p.setPaymentDeadline(deadline.plusSeconds(1));}
            when(payments.findByIdempotencyKey("key")).thenReturn(Optional.of(p));
            assertThatThrownBy(()->gateway.createRenewalPaymentUrl(actor,renewalId,"key","127.0.0.1")).hasMessageContaining("binding changed");
        }
    }
    Payment existing(){var p=id(new Payment());p.setRenewal(n);p.setRenewalRevision(revision);p.setInitiatedBy(user);p.setAmount(new BigDecimal("100"));p.setCurrency("VND");p.setPurpose(PaymentType.RENEWAL);p.setGatewayProvider("VNPAY");p.setGatewayIntentId("test-intent");p.setPaymentDeadline(deadline);return p;}
}
