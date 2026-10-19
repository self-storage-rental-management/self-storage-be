package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentProvenanceTests {
    final PaymentComplaintRepository complaints=mock(PaymentComplaintRepository.class);
    final ActivityLogRepository logs=mock(ActivityLogRepository.class);
    final PaymentProvenance classifier=new PaymentProvenance(complaints,logs,new ObjectMapper());
    static <T extends BaseEntity>T identified(T e){ReflectionTestUtils.setField(e,"id",UUID.randomUUID());return e;}
    Payment payment(){var p=identified(new Payment());var r=identified(new Reservation());r.setCustomer(identified(new User()));r.setFacility(identified(new Facility()));p.setReservation(r);p.setStatus(PaymentStatus.PAID);p.setPurpose(PaymentType.RESERVATION_DEPOSIT);p.setAmount(new BigDecimal("100"));p.setCurrency("VND");p.setGatewayProvider("VNPAY");p.setGatewayIntentId("intent");p.setGatewayTransactionNo("transaction");p.setGatewayResponseCode("00");p.setGatewayTransactionStatus("00");p.setPaidAt(Instant.now());r.setPaymentExpiresAt(p.getPaidAt().plusSeconds(600));return p;}
    ActivityLog proof(Payment p,String environment)throws Exception{var a=identified(new ActivityLog());a.setFacility(p.getReservation().getFacility());var data=new LinkedHashMap<String,Object>(Map.of("paymentId",p.getId(),"reservationId",p.getReservation().getId(),"customerId",p.getReservation().getCustomer().getId(),"intentId",p.getGatewayIntentId(),"transactionNo",p.getGatewayTransactionNo(),"purpose",p.getPurpose(),"currency","VND","amount",p.getAmount(),"signatureVerified",true,"environment",environment));data.put("bookingPaymentAccepted",true);data.put("gatewayPaidAt",p.getPaidAt().toString());data.put("paymentDeadline",p.getReservation().getPaymentExpiresAt().toString());a.setAfterStateJson(new ObjectMapper().writeValueAsString(data));return a;}
    void evidence(Payment p,ActivityLog...a){when(logs.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc("Payment",p.getId(),PaymentProvenance.CALLBACK_ACTION)).thenReturn(List.of(a));}
    @Test void simulatedPaidAndLegacyGatewayResultsRemainNonproduction(){var p=payment();p.setGatewayIntentId("SIMULATED-1");assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.SIMULATED);p.setGatewayIntentId("intent");assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.UNVERIFIED);}
    @Test void signedSandboxProofCannotBePromotedByConfigurationChange()throws Exception{var p=payment();evidence(p,proof(p,"SANDBOX"));assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.SANDBOX);}
    @Test void productionProofRequiresMatchingIdentityAndUniqueProof()throws Exception{var p=payment();var a=proof(p,"PRODUCTION");evidence(p,a);assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.VERIFIED);p.setAmount(new BigDecimal("101"));assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.UNVERIFIED);p.setAmount(new BigDecimal("100"));evidence(p,a,a);assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.UNVERIFIED);}
    @Test void pendingComplaintAndComplaintApprovalCannotBecomeVerified()throws Exception{var p=payment();evidence(p,proof(p,"PRODUCTION"));var complaint=new PaymentComplaint();complaint.setPayment(p);complaint.setStatus(PaymentComplaintStatus.PENDING);when(complaints.findByReservation_Id(p.getReservation().getId())).thenReturn(Optional.of(complaint));assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.DISPUTED);complaint.setStatus(PaymentComplaintStatus.APPROVED);assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.UNVERIFIED);}
    @Test void gatewayAttemptIsNotVerifiedEvenWithEvidence()throws Exception{var p=payment();evidence(p,proof(p,"PRODUCTION"));p.setStatus(PaymentStatus.PENDING);assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.ATTEMPT);}
    @Test void latePaidWithOtherwiseValidProofRemainsUnverified()throws Exception{var p=payment();evidence(p,proof(p,"PRODUCTION"));p.setFailureCode("PAYMENT_AFTER_DEADLINE");assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.UNVERIFIED);}
    @Test void expiredOrChangedDeadlineCannotCertifyAvailableCredit()throws Exception{var p=payment();evidence(p,proof(p,"PRODUCTION"));p.getReservation().setPaymentExpiresAt(p.getPaidAt().minusSeconds(1));assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.UNVERIFIED);}
    @Test void legacyProofWithoutAcceptanceContextRemainsUnknown()throws Exception{var p=payment();var a=proof(p,"PRODUCTION");var data=new ObjectMapper().readTree(a.getAfterStateJson());((com.fasterxml.jackson.databind.node.ObjectNode)data).remove("bookingPaymentAccepted");a.setAfterStateJson(data.toString());evidence(p,a);assertThat(classifier.classify(p)).isEqualTo(PaymentProvenance.Classification.UNVERIFIED);}
}
