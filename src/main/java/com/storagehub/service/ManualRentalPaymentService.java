package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.renewal.persistence.RenewalAcceptedRevision;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Shared recording of Staff-attested CASH/BANK_TRANSFER. Authorization and source locks belong to callers. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class ManualRentalPaymentService {
    public static final String AUDIT_ACTION="MANUAL_RENTAL_BALANCE_RECORDED_V1";
    private final PaymentRepository payments;
    private final AuditLogService audit;

    public record Recorded(Payment payment,boolean created) {}
    public Recorded booking(Reservation reservation,UUID checkIn,User actor,BigDecimal expected,BigDecimal amount,String method,String reference,Instant now) {
        return booking(reservation,checkIn,actor,expected,amount,method,reference,null,now);
    }
    public Recorded booking(Reservation reservation,UUID checkIn,User actor,BigDecimal expected,BigDecimal amount,String method,String reference,UUID evidenceFileId,Instant now) {
        return record(reservation,null,null,actor,expected,amount,method,reference,"CHECKIN-BALANCE-"+checkIn+"-"+reference,evidenceFileId,now);
    }
    public Recorded renewal(Renewal renewal,RenewalAcceptedRevision revision,User actor,BigDecimal expected,String method,String reference,String key,UUID file,Instant now) {
        return renewal(renewal,revision,actor,expected,method,reference,key,file,now,false);
    }
    public Recorded renewalSettlement(Renewal renewal,RenewalAcceptedRevision revision,User actor,BigDecimal expected,String method,String reference,String key,UUID file,Instant now) {
        return renewal(renewal,revision,actor,expected,method,reference,key,file,now,true);
    }
    private Recorded renewal(Renewal renewal,RenewalAcceptedRevision revision,User actor,BigDecimal expected,String method,String reference,String key,UUID file,Instant now,boolean supplemental) {
        if(revision==null||!renewal.getId().equals(revision.getRenewal().getId())||file==null)throw ApiExceptions.conflict("Accepted Renewal revision and receipt evidence required");
        String scoped="RENEWAL-BALANCE-"+renewal.getId()+"-"+UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return record(null,renewal,revision,actor,expected,expected,method,reference,scoped,file,now,supplemental);
    }
    private Recorded record(Reservation reservation,Renewal renewal,RenewalAcceptedRevision revision,User actor,BigDecimal expected,
        BigDecimal amount,String method,String reference,String key,UUID file,Instant now) {
        return record(reservation,renewal,revision,actor,expected,amount,method,reference,key,file,now,false);
    }
    private Recorded record(Reservation reservation,Renewal renewal,RenewalAcceptedRevision revision,User actor,BigDecimal expected,
        BigDecimal amount,String method,String reference,String key,UUID file,Instant now,boolean supplemental) {
        if((reservation==null)==(renewal==null)||actor==null||expected==null||amount==null||amount.signum()<=0||amount.compareTo(expected)!=0)
            throw ApiExceptions.validation("Payment amount must match the server balance",null);
        method=method==null?"CASH":method.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("CASH","BANK_TRANSFER").contains(method))throw ApiExceptions.validation("paymentMethod must be CASH or BANK_TRANSFER",null);
        reference=reference==null?"":reference.trim();
        if(reference.isBlank()||reference.length()>100)throw ApiExceptions.validation("Actual payment reference required",null);
        final String ref=reference;
        var existing=payments.findByIdempotencyKey(key);
        if(existing.isEmpty())existing=reservation!=null?payments.findTopByReservation_IdAndPurposeAndGatewayTransactionNo(reservation.getId(),PaymentType.RENTAL_BALANCE,ref):
            payments.findTopByRenewal_IdAndPurposeAndGatewayTransactionNo(renewal.getId(),PaymentType.RENTAL_BALANCE,ref);
        if(existing.isPresent()) {
            var p=existing.get();
            if(!Objects.equals(p.getReservation()==null?null:p.getReservation().getId(),reservation==null?null:reservation.getId())
                ||!Objects.equals(p.getRenewal()==null?null:p.getRenewal().getId(),renewal==null?null:renewal.getId())
                ||!Objects.equals(p.getRenewalRevision()==null?null:p.getRenewalRevision().getId(),revision==null?null:revision.getId())
                ||p.getPurpose()!=PaymentType.RENTAL_BALANCE||p.getStatus()!=PaymentStatus.PAID||!"MANUAL".equals(p.getGatewayProvider())
                ||p.getAmount().compareTo(amount)!=0||!"VND".equals(p.getCurrency())||!method.equals(p.getGatewayBankCode())
                ||!reference.equals(p.getGatewayTransactionNo())||p.getRefundedAmount()==null||p.getRefundedAmount().signum()!=0)
                throw ApiExceptions.conflict("Payment reference or key already has different terms");
            return new Recorded(p,false);
        }
        if(renewal!=null&&!supplemental&&payments.findAllByRenewal_IdAndStatusIn(renewal.getId(),List.of(PaymentStatus.PAID)).stream()
            .anyMatch(p->p.getPurpose()==PaymentType.RENTAL_BALANCE&&p.getRenewalRevision()!=null&&p.getRenewalRevision().getId().equals(revision.getId())))
            throw ApiExceptions.conflict("Remaining Renewal rent has already been recorded");
        now=now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var p=new Payment();p.setInitiatedBy(actor);p.setReservation(reservation);p.setRenewal(renewal);p.setRenewalRevision(revision);
        p.setAmount(amount);p.setCurrency("VND");p.setPurpose(PaymentType.RENTAL_BALANCE);p.setStatus(PaymentStatus.PAID);
        p.setIdempotencyKey(key);p.setGatewayIntentId("MANUAL-RENTAL-"+UUID.randomUUID());p.setGatewayProvider("MANUAL");
        p.setGatewayTransactionNo(reference);p.setGatewayBankCode(method);p.setGatewayResponseCode("00");p.setGatewayTransactionStatus("PAID");
        p.setPaidAt(now);p.setProcessedAt(now);payments.saveAndFlush(p);
        var proof=new LinkedHashMap<String,Object>();proof.put("paymentId",p.getId());proof.put("reservationId",reservation==null?null:reservation.getId());
        proof.put("renewalId",renewal==null?null:renewal.getId());proof.put("acceptedRevisionId",revision==null?null:revision.getId());
        proof.put("amount",amount);proof.put("currency","VND");proof.put("paymentMethod",method);proof.put("transactionReference",reference);
        proof.put("initiatedBy",actor.getId());proof.put("paidAt",now.toString());proof.put("receiptFileId",file);
        audit.recordMutation(actor,AUDIT_ACTION,"Payment",p.getId(),reservation!=null?reservation.getFacility().getId():renewal.getRental().getFacility().getId(),null,proof);
        return new Recorded(p,true);
    }
}
