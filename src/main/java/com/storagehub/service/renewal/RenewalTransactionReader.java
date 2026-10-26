package com.storagehub.service.renewal;

import com.storagehub.api.renewal.RenewalStatusSummary;
import com.storagehub.domain.model.*;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.ledger.LedgerStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class RenewalTransactionReader {
    private final ObjectProvider<LedgerStore> ledgers;
    private final PaymentProvenance provenance;
    public RenewalStatusSummary.Transaction read(Payment payment,Instant now) {
        var ledger=ledgers.getIfAvailable();
        return project(payment,ledger==null?Optional.empty():ledger.read(payment.getRenewal().getRental().getId()),now);
    }
    public RenewalStatusSummary.Transaction project(Payment p,Optional<LedgerStore.Snapshot> account,Instant now) {
        var receipt=account.stream().flatMap(s->s.receipts().stream()).filter(r->r.id().equals(p.getRenewalLedgerReceiptRef())).findFirst().orElse(null);
        boolean bound=verifiedAllocation(p,account);
        BigDecimal refunded=receipt==null?BigDecimal.ZERO:account.get().refunds().stream()
            .filter(r->r.receiptId().equals(receipt.id())&&"EXECUTED".equals(r.status())&&r.payoutRef()!=null&&!r.payoutRef().isBlank())
            .map(LedgerStore.Refund::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        String status=RenewalTransactionStatus.resolve(p,bound,refunded,now);
        return new RenewalStatusSummary.Transaction(p.getId(),p.getGatewayIntentId(),p.getPurpose().name(),status,p.getAmount(),refunded,p.getCurrency(),
            p.getPaymentDeadline(),p.getPaymentDeadline()!=null&&!now.isBefore(p.getPaymentDeadline()),
            bound&&Set.of("SUCCEEDED","PARTIALLY_REFUNDED","REFUNDED").contains(status),p.getPaidAt());
    }
    public boolean verifiedAllocation(Payment p,Optional<LedgerStore.Snapshot> account) {
        var receipt=account.stream().flatMap(s->s.receipts().stream()).filter(r->r.id().equals(p.getRenewalLedgerReceiptRef())).findFirst().orElse(null);
        return receipt!=null&&p.getStatus()==PaymentStatus.PAID&&p.getRenewalAllocatedAt()!=null&&p.getPaidAt()!=null
            &&p.getAmount()!=null&&receipt.amount().compareTo(p.getAmount())==0
            &&account.get().rentalId().equals(p.getRenewal().getRental().getId())
            &&receipt.receivedAt().toEpochMilli()==p.getPaidAt().toEpochMilli()
            &&account.get().customerId().equals(p.getRenewal().getRental().getCustomer().getId())
            &&account.get().facilityId().equals(p.getRenewal().getRental().getFacility().getId())
            &&Set.of(PaymentProvenance.Classification.VERIFIED,PaymentProvenance.Classification.SANDBOX).contains(provenance.classify(p));
    }
}
