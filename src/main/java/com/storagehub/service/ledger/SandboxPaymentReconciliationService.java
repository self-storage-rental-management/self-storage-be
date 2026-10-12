package com.storagehub.service.ledger;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.ActivityLogRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
public class SandboxPaymentReconciliationService {
    public record Result(UUID receiptId,long ledgerRevision,String method) {}
    private final EntityManager em;
    private final DuongResourceAccess access;
    private final LedgerStore ledger;
    private final PaymentProvenance provenance;
    private final SandboxPaymentRecognitionPolicy policy;
    private final PaymentReconciliationPreflight preflight;
    private final ActivityLogRepository logs;
    public Result reconcile(ActorPrincipal actor,UUID rentalId,UUID paymentId,long revision,String key) {
        if(actor==null)throw ApiExceptions.unauthorized("Authentication required");
        em.find(User.class,actor.userId(),LockModeType.PESSIMISTIC_WRITE);
        var rental=em.find(Rental.class,rentalId);
        if(rental==null)throw ApiExceptions.notFound("Rental not found");
        if(rental.getFacility()==null||rental.getCustomer()==null||rental.getReservation()==null)throw ApiExceptions.conflict("Rental source identity incomplete");
        access.require(actor,RoleCode.MANAGER,rental.getFacility().getId(),FacilityScopeLevel.MANAGE,SystemPermission.VIEW_RENTALS,SystemPermission.VIEW_PAYMENTS,SystemPermission.MANAGE_PAYMENTS);
        var payment=em.find(Payment.class,paymentId,LockModeType.PESSIMISTIC_WRITE);
        if(payment==null)throw ApiExceptions.notFound("Payment not found");
        if(payment.getReservation()!=null)em.lock(payment.getReservation(),LockModeType.PESSIMISTIC_WRITE);
        // VNPay locks Payment before Reservation; activation locks Reservation before Rental.
        em.refresh(rental,LockModeType.PESSIMISTIC_WRITE);
        if(rental.getFacility()==null||rental.getCustomer()==null||rental.getReservation()==null)throw ApiExceptions.conflict("Rental source identity incomplete");
        access.require(actor,RoleCode.MANAGER,rental.getFacility().getId(),FacilityScopeLevel.MANAGE,SystemPermission.VIEW_RENTALS,SystemPermission.VIEW_PAYMENTS,SystemPermission.MANAGE_PAYMENTS);
        var classification=provenance.classify(payment);
        if(!policy.evaluate(payment,classification).eligibleForReconciliation())throw ApiExceptions.conflict("Authenticated project Sandbox evidence required");
        var snapshots=em.createQuery("select s from ReservationPricingSnapshot s where s.reservation.id=:id",ReservationPricingSnapshot.class).setParameter("id",rental.getReservation().getId()).getResultList();
        if(snapshots.size()!=1)throw ApiExceptions.conflict("Authoritative pricing snapshot required");
        var plan=preflight.read(payment,rental,snapshots.getFirst(),classification);
        if(!Set.of("SANDBOX_RECEIPT_READY","PAYMENT_ALREADY_REFERENCED").contains(plan.status()))throw ApiExceptions.conflict("Payment reconciliation blocked: "+plan.status());
        var spec=FinancialContractSpecifications.initial(rental,snapshots.getFirst()).getFirst();
        var account=ledger.read(rentalId).orElseThrow(()->ApiExceptions.conflict("Ledger account missing"));
        var targets=account.obligations().stream().filter(o->o.kind()==LedgerStore.Kind.RENT&&o.renewalId()==null&&spec.sourceReference().equals(o.sourceRef())&&o.amount().compareTo(spec.amount())==0).toList();
        if(targets.size()!=1)throw ApiExceptions.conflict("Authoritative RENT obligation required");
        var proofs=logs.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc("Payment",paymentId,PaymentProvenance.CALLBACK_ACTION);
        if(proofs.size()!=1)throw ApiExceptions.conflict("Unique callback proof required");
        var proof=new LedgerStore.GatewayProof(paymentId,proofs.getFirst().getId(),payment.getGatewayTransactionNo());
        UUID receipt=ledger.receiveSandbox(rentalId,payment.getAmount(),Map.of(targets.getFirst().id(),payment.getAmount()),proof,actor.userId(),key,revision,payment.getPaidAt());
        return new Result(receipt,ledger.read(rentalId).orElseThrow().revision(),LedgerStore.Method.VNPAY_SANDBOX.name());
    }
}
