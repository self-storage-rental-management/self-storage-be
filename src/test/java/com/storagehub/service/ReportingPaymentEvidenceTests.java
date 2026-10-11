package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReportingPaymentEvidenceTests {
    @Test void reportingUsesSharedClassificationAndExplicitProductionFlag() {
        var source=org.mockito.Mockito.mock(PaymentProvenance.class);var payment=paidGatewayPayment();
        var enabled=new ReportingPaymentEvidence(source,true);
        for(var classification:PaymentProvenance.Classification.values()) {
            org.mockito.Mockito.when(source.classify(payment)).thenReturn(classification);
            assertThat(enabled.isRealCollected(payment)).isEqualTo(classification==PaymentProvenance.Classification.VERIFIED);
        }
        org.mockito.Mockito.when(source.classify(payment)).thenReturn(PaymentProvenance.Classification.VERIFIED);
        assertThat(new ReportingPaymentEvidence(source,false).isRealCollected(payment)).isFalse();
    }
    private PaymentProvenance classifier() { return org.mockito.Mockito.mock(PaymentProvenance.class); }
    private Payment paidGatewayPayment() {
        var payment = new Payment();
        payment.setStatus(PaymentStatus.PAID);
        payment.setGatewayProvider("VNPAY");
        payment.setGatewayResponseCode("00");
        payment.setGatewayTransactionStatus("00");
        payment.setGatewayTransactionNo("gateway-transaction");
        payment.setPaidAt(Instant.parse("2026-10-11T00:00:00Z"));
        return payment;
    }

    @Test
    void paidStatusAndSandboxDoNotProveRealCollectedRevenue() {
        var config = new VnpayProperties();
        config.setEnabled(true);
        var evidence = new ReportingPaymentEvidence(classifier(), true);
        assertThat(evidence.isRealCollected(paidGatewayPayment())).isFalse();
        var simulated = paidGatewayPayment();
        simulated.setGatewayProvider(null);
        simulated.setGatewayIntentId("SIMULATED-test");
        assertThat(evidence.isRealCollected(simulated)).isFalse();
        var complaintApproved = paidGatewayPayment();
        complaintApproved.setGatewayProvider(null);
        complaintApproved.setGatewayTransactionNo(null);
        assertThat(evidence.isRealCollected(complaintApproved)).isFalse();
    }

    @Test
    void explicitRealGatewayConfigurationAndTransactionEvidenceAreRequired() {
        var config = new VnpayProperties();
        config.setEnabled(true);
        config.setPayUrl("https://pay.vnpay.vn/paymentv2/vpcpay.html");
        config.setApiUrl("https://merchant.vnpay.vn/merchant_webapi/api/transaction");
        var payment = paidGatewayPayment();
        assertThat(new ReportingPaymentEvidence(classifier(), false).isRealCollected(payment)).isFalse();
        var evidence = new ReportingPaymentEvidence(classifier(), true);
        assertThat(evidence.isRealCollected(payment)).isFalse();
        payment.setGatewayTransactionNo(null);
        assertThat(evidence.isRealCollected(payment)).isFalse();
    }
}
