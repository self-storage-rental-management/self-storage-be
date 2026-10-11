package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only classification; current gateway configuration cannot establish historical provenance. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class PaymentProvenance {
    public enum Classification { VERIFIED, SIMULATED, SANDBOX, UNVERIFIED, DISPUTED, ATTEMPT }
    public static final String CALLBACK_ACTION="VNPAY_AUTHENTICATED_CALLBACK_V1";
    private final PaymentComplaintRepository complaints;
    private final ActivityLogRepository logs;
    private final ObjectMapper mapper;
    public Classification classify(Payment p) {
        if(p==null || p.getReservation()==null || p.getId()==null)return Classification.UNVERIFIED;
        var complaint=complaints.findByReservation_Id(p.getReservation().getId());
        if(complaint.isPresent() && complaint.get().getPayment()!=null && Objects.equals(complaint.get().getPayment().getId(),p.getId())) {
            var s=complaint.get().getStatus();
            if(s==PaymentComplaintStatus.PENDING || s==PaymentComplaintStatus.REVIEW_OVERDUE)return Classification.DISPUTED;
            if(s==PaymentComplaintStatus.APPROVED)return Classification.UNVERIFIED;
        }
        if(p.getGatewayIntentId()!=null && p.getGatewayIntentId().startsWith("SIMULATED-"))return Classification.SIMULATED;
        // Late funds are the Payment owner's reconciliation case, not available Rental credit.
        if("PAYMENT_AFTER_DEADLINE".equals(p.getFailureCode()))return Classification.UNVERIFIED;
        if(p.getStatus()!=PaymentStatus.PAID)return "VNPAY".equals(p.getGatewayProvider())
            && (p.getStatus()==PaymentStatus.PENDING || p.getStatus()==PaymentStatus.PROCESSING)?Classification.ATTEMPT:Classification.UNVERIFIED;
        if(!"VNPAY".equals(p.getGatewayProvider()) || !"VND".equals(p.getCurrency()) || p.getPaidAt()==null
            || !"00".equals(p.getGatewayResponseCode()) || !"00".equals(p.getGatewayTransactionStatus())
            || p.getGatewayTransactionNo()==null || p.getGatewayTransactionNo().isBlank() || p.getAmount()==null)return Classification.UNVERIFIED;
        var proof=logs.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc("Payment",p.getId(),CALLBACK_ACTION);
        if(proof.size()!=1)return Classification.UNVERIFIED;
        try {
            var log=proof.getFirst();var d=mapper.readTree(log.getAfterStateJson());var res=p.getReservation();
            // Older proof without accepted-payment/date context cannot certify current credit.
            var deadline=res.getPaymentExpiresAt()!=null?res.getPaymentExpiresAt():res.getHoldExpiresAt();
            if(!d.path("bookingPaymentAccepted").asBoolean() || deadline==null)return Classification.UNVERIFIED;
            var gatewayPaidAt=java.time.Instant.parse(d.path("gatewayPaidAt").asText());
            if(!gatewayPaidAt.equals(p.getPaidAt()) || gatewayPaidAt.isAfter(deadline)
                || !deadline.equals(java.time.Instant.parse(d.path("paymentDeadline").asText())))return Classification.UNVERIFIED;
            if(log.getFacility()==null || !Objects.equals(log.getFacility().getId(),res.getFacility().getId())
                || !p.getId().toString().equals(d.path("paymentId").asText())
                || !res.getId().toString().equals(d.path("reservationId").asText())
                || !res.getCustomer().getId().toString().equals(d.path("customerId").asText())
                || !Objects.equals(p.getGatewayIntentId(),d.path("intentId").asText())
                || !p.getGatewayTransactionNo().equals(d.path("transactionNo").asText())
                || !p.getPurpose().name().equals(d.path("purpose").asText())
                || !"VND".equals(d.path("currency").asText()) || !d.path("signatureVerified").asBoolean()
                || p.getAmount().compareTo(new java.math.BigDecimal(d.path("amount").asText()))!=0)return Classification.UNVERIFIED;
            return switch(d.path("environment").asText()) {case "PRODUCTION"->Classification.VERIFIED;case "SANDBOX"->Classification.SANDBOX;default->Classification.UNVERIFIED;};
        } catch(Exception invalid){return Classification.UNVERIFIED;}
    }
    public static String environment(String payUrl,String apiUrl) {
        try {var pay=URI.create(payUrl);var api=URI.create(apiUrl);
            if(!"https".equalsIgnoreCase(pay.getScheme())||!"https".equalsIgnoreCase(api.getScheme()))return "UNKNOWN";
            if("pay.vnpay.vn".equalsIgnoreCase(pay.getHost())&&"merchant.vnpay.vn".equalsIgnoreCase(api.getHost()))return "PRODUCTION";
            if("sandbox.vnpayment.vn".equalsIgnoreCase(pay.getHost())&&"sandbox.vnpayment.vn".equalsIgnoreCase(api.getHost()))return "SANDBOX";
        }catch(Exception ignored){}return "UNKNOWN";
    }
}
