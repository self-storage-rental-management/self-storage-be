package com.storagehub.service.renewal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.ledger.RenewalDepositForfeitures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Return and renewal commands serialize on the parent Rental before applying this policy. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalReturnPolicy {
    private final EntityManager em;
    private final RentalRepository rentals;
    private final PaymentRepository payments;
    private final ObjectMapper mapper;
    private final ObjectProvider<RenewalDepositForfeitures> forfeitures;
    private static final Set<RenewalStatus> CLOSED = Set.of(RenewalStatus.cancelled,RenewalStatus.rejected,RenewalStatus.payment_expired);
    public record View(UUID rentalId, boolean allowed, UUID renewalId, String state, String message,
        BigDecimal paidAmount, BigDecimal depositAmount) {}

    public View customer(ActorPrincipal actor, UUID rentalId) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) throw ApiExceptions.forbidden("Chỉ khách hàng được xem điều kiện trả kho.");
        var rental=rentals.findByIdAndCustomer_Id(rentalId,actor.userId())
            .orElseThrow(()->ApiExceptions.notFound("Không tìm thấy hồ sơ thuê."));
        return read(rental);
    }

    public View read(Rental rental) {
        var query=em.createQuery("select n from Renewal n where n.rental.id=:id and n.status<>:completed order by n.createdAt,n.id",Renewal.class)
            .setParameter("id",rental.getId()).setParameter("completed",RenewalStatus.completed);
        if (writeTransaction()) query.setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var candidates=query.getResultList();
        for (var renewal:candidates) {
            var rows=paymentRows(renewal.getId());
            if (CLOSED.contains(renewal.getStatus()) && resolved(rows)) continue;
            var view=assess(renewal,rows);
            if (CLOSED.contains(renewal.getStatus())) return review(renewal,view.paidAmount(),view.depositAmount());
            return view;
        }
        return new View(rental.getId(),true,null,"ALLOWED",null,BigDecimal.ZERO,BigDecimal.ZERO);
    }

    public View assess(Renewal renewal) {
        return assess(renewal,paymentRows(renewal.getId()));
    }

    private List<Payment> paymentRows(UUID id) {
        return writeTransaction() ? payments.findRenewalPaymentsForUpdate(id) : payments.findAllByRenewal_IdOrderByCreatedAtAsc(id);
    }

    private boolean writeTransaction() {
        return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    public boolean canCancel(Renewal renewal) {
        if (renewal.getStatus()==RenewalStatus.completed || CLOSED.contains(renewal.getStatus())) return false;
        String state=assess(renewal).state();
        return state.equals("FULLY_PAID") || state.equals("DEPOSIT_PAID") || state.equals("UNPAID")
            && (renewal.getStatus()==RenewalStatus.pending || renewal.getStatus()==RenewalStatus.accepted);
    }

    private View assess(Renewal n,List<Payment> rows) {
        BigDecimal paid=rows.stream().filter(p->p.getStatus()==PaymentStatus.PAID)
            .map(Payment::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal deposit=BigDecimal.ZERO,remaining=BigDecimal.ZERO,total=BigDecimal.ZERO;
        var workflow=em.find(com.storagehub.service.renewal.persistence.RenewalWorkflow.class,n.getId());
        if (workflow!=null && workflow.getAcceptedRevision()!=null) try {
            var terms=mapper.readTree(workflow.getAcceptedRevision().getQuote().getTermsJson());
            deposit=terms.path("renewalDepositAmount").decimalValue();remaining=terms.path("remainingRentalAmount").decimalValue();
            total=terms.path("totalAfterDiscount").decimalValue();
        } catch (Exception ignored) { return review(n,paid,deposit); }
        boolean unresolved=rows.stream().anyMatch(p->!Set.of(PaymentStatus.PAID,PaymentStatus.FAILED,PaymentStatus.CANCELLED).contains(p.getStatus())
            || p.getStatus()!=PaymentStatus.PAID && (p.getPaidAt()!=null || p.getRenewalLedgerReceiptRef()!=null));
        if (unresolved) return review(n,paid,deposit);
        if (paid.signum()==0) return new View(n.getRental().getId(),false,n.getId(),"UNPAID",
            "Hồ sơ đang có yêu cầu gia hạn chưa thanh toán. Vui lòng vào Gia hạn để hủy yêu cầu trước khi trả kho.",paid,deposit);
        var received=rows.stream().filter(p->p.getStatus()==PaymentStatus.PAID).toList();
        var signing=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,n.getId());
        if (total.signum()>0 && paid.compareTo(total)>=0 && signing!=null && signing.hasVerifiedPayment()
            && received.stream().allMatch(p->p.getRenewalLedgerReceiptRef()!=null && p.getPaidAt()!=null
                && p.getRefundedAmount()!=null && p.getRefundedAmount().signum()==0)) {
            return new View(n.getRental().getId(),false,n.getId(),"FULLY_PAID",
                "Bạn đã thanh toán đủ tiền gia hạn. Vui lòng vào Gia hạn để hủy và xác nhận toàn bộ tiền gia hạn đã thanh toán không được hoàn lại trước khi trả kho.",paid,deposit);
        }
        if (remaining.signum()>0 && deposit.signum()>0 && paid.compareTo(deposit)==0 && received.size()==1
            && signing!=null && signing.getDepositPaidAt()!=null && signing.getFullRentPaidAt()==null
            && received.getFirst().getRenewalLedgerReceiptRef()!=null
            && received.getFirst().getRefundedAmount()!=null && received.getFirst().getRefundedAmount().signum()==0) {
            return new View(n.getRental().getId(),false,n.getId(),"DEPOSIT_PAID",
                "Bạn đã thanh toán cọc gia hạn. Vui lòng vào Gia hạn để hủy yêu cầu trước khi trả kho. Tiền cọc gia hạn không được hoàn lại khi bạn tự hủy.",paid,deposit);
        }
        return review(n,paid,deposit);
    }

    private View review(Renewal n,BigDecimal paid,BigDecimal deposit) {
        return new View(n.getRental().getId(),false,n.getId(),"RECONCILIATION_REQUIRED",
            "Khoản thanh toán gia hạn cần được cơ sở đối soát trước khi hủy gia hạn và trả kho. Vui lòng liên hệ cơ sở; các thao tác trả kho đang tạm khóa.",paid,deposit);
    }

    private boolean resolved(List<Payment> rows) {
        var source=forfeitures.getIfAvailable();
        return rows.stream().allMatch(p->Set.of(PaymentStatus.FAILED,PaymentStatus.CANCELLED).contains(p.getStatus())
            && p.getPaidAt()==null && p.getRenewalLedgerReceiptRef()==null
            || p.getStatus()==PaymentStatus.PAID && (p.getAmount()!=null && p.getRefundedAmount()!=null && p.getRefundedAmount().compareTo(p.getAmount())>=0
                || source!=null && p.getRenewalLedgerReceiptRef()!=null && source.containsReceipt(p.getRenewalLedgerReceiptRef())));
    }

    public void requireAllowed(Rental rental) {
        var view=read(rental);
        if (!view.allowed()) throw ApiExceptions.conflict(view.message());
    }
}
