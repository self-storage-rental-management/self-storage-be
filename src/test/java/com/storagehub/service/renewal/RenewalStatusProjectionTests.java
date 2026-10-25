package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.api.renewal.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.ledger.LedgerStore;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RenewalStatusProjectionTests {
    @Test void blockedEligibilityPausesWorkflowWithoutRewritingInvoiceOrTransactionStates(){
        var invoice=RenewalStatusSummary.Invoice.unknown("NOT_ISSUED","NO_POSTED_OBLIGATIONS");
        var summary=new RenewalStatusSummary("AWAITING_PAYMENT",invoice,List.of(),NOW);
        var verification=new RenewalResponse.SystemVerification("BLOCKED",NOW,List.of(),List.of("Return case blocks renewal"));
        var result=RenewalStatusProjection.applyVerification(summary,verification,"READY");
        assertThat(result.workflowStatus()).isEqualTo("ON_HOLD");assertThat(result.invoice()).isSameAs(invoice);
    }
    static final Instant NOW=Instant.parse("2026-10-25T08:00:00Z");
    static final UUID RECEIPT=UUID.randomUUID(),DEPOSIT=UUID.randomUUID(),BALANCE=UUID.randomUUID(),RENEWAL=UUID.randomUUID();
    static BigDecimal n(String v){return new BigDecimal(v);}
    static RenewalQuoteResponse.Terms terms(){
        var start=LocalDate.of(2026,11,1);
        return new RenewalQuoteResponse.Terms(start.minusDays(1),start,start.plusMonths(1),start.plusMonths(1).minusDays(1),
            UUID.randomUUID(),"1M",UUID.randomUUID(),"v1",1,n("5000000"),BigDecimal.ZERO,n("5000000"),BigDecimal.ZERO,
            n("5000000"),n("1000000"),n("4000000"),"VND","policy","v1",UUID.randomUUID(),UUID.randomUUID());
    }
    static LedgerStore.Obligation obligation(UUID id,String amount,String allocated,String refunded,String outstanding,Instant due){
        return new LedgerStore.Obligation(id,RENEWAL,id.equals(DEPOSIT)?LedgerStore.Kind.RENEWAL_DEPOSIT:LedgerStore.Kind.RENEWAL_REMAINDER,
            n(amount),due,"renewal-accepted:test:"+id,n(allocated),n(refunded),n(outstanding));
    }
    static List<LedgerStore.Obligation> partial(){return List.of(
        obligation(DEPOSIT,"1000000","1000000","0","0",NOW.minusSeconds(100)),
        obligation(BALANCE,"4000000","0","0","4000000",NOW.plusSeconds(100)));}
    static List<LedgerStore.Allocation> prepayment(){return List.of(new LedgerStore.Allocation(RECEIPT,DEPOSIT,n("1000000")));}
    @Test void successfulPrepaymentLeavesInvoicePartiallyPaidAndBalanceDueAtSigning(){
        var result=RenewalStatusProjection.invoice(terms(),partial(),prepayment(),Set.of(RECEIPT),List.of(),BigDecimal.ZERO,NOW,false);
        assertThat(result.status()).isEqualTo("PARTIALLY_PAID");
        assertThat(result.paidAmount()).isEqualByComparingTo("1000000");
        assertThat(result.outstandingAmount()).isEqualByComparingTo("4000000");
        assertThat(result.dueNowAmount()).isZero();
        assertThat(result.overdueAmount()).isZero();
    }
    @Test void invoicePaidDoesNotCompleteWorkflow(){
        var obligations=List.of(partial().getFirst(),obligation(BALANCE,"4000000","4000000","0","0",NOW));
        var allocations=List.of(prepayment().getFirst(),new LedgerStore.Allocation(RECEIPT,BALANCE,n("4000000")));
        assertThat(RenewalStatusProjection.invoice(terms(),obligations,allocations,Set.of(RECEIPT),List.of(),BigDecimal.ZERO,NOW,false).status()).isEqualTo("PAID");
        var renewal=new Renewal();renewal.setStatus(RenewalStatus.deposit_paid);
        var state=new RenewalOperationState(renewal);state.deposit(UUID.randomUUID(),NOW.minusSeconds(1),NOW.plusSeconds(300),NOW.plusSeconds(600),"p","v1");
        var payment=new RenewalStatusSummary.Transaction(UUID.randomUUID(),"tx","RENEWAL","SUCCEEDED",n("5000000"),BigDecimal.ZERO,"VND",NOW.plusSeconds(50),false,true,NOW);
        assertThat(RenewalStatusProjection.workflow(renewal,state,null,List.of(payment),NOW)).isEqualTo("AWAITING_SIGNING");
    }
    @Test void unverifiedAllocationsCannotCertifyInvoicePayment(){
        var result=RenewalStatusProjection.invoice(terms(),partial(),prepayment(),Set.of(),List.of(),BigDecimal.ZERO,NOW,false);
        assertThat(result.status()).isEqualTo("UNKNOWN");assertThat(result.paidAmount()).isNull();
    }
    @Test void pendingRefundDoesNotSubtractMoneyUntilExecuted(){
        var refund=new LedgerStore.Refund(UUID.randomUUID(),RECEIPT,DEPOSIT,n("500000"),"RESERVED","decision",null);
        var result=RenewalStatusProjection.invoice(terms(),partial(),prepayment(),Set.of(RECEIPT),List.of(refund),BigDecimal.ZERO,NOW,false);
        assertThat(result.refundStatus()).isEqualTo("PENDING");assertThat(result.refundedAmount()).isZero();
        assertThat(result.paidAmount()).isEqualByComparingTo("1000000");
    }
    @Test void partialRefundReopensDebtWithoutCallingTransactionFailed(){
        var obligations=List.of(obligation(DEPOSIT,"1000000","1000000","500000","500000",NOW.minusSeconds(1)),partial().getLast());
        var refund=new LedgerStore.Refund(UUID.randomUUID(),RECEIPT,DEPOSIT,n("500000"),"EXECUTED","decision","payout");
        var result=RenewalStatusProjection.invoice(terms(),obligations,prepayment(),Set.of(RECEIPT),List.of(refund),BigDecimal.ZERO,NOW,false);
        assertThat(result.status()).isEqualTo("PARTIALLY_PAID");assertThat(result.refundStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(result.paidAmount()).isEqualByComparingTo("500000");assertThat(result.outstandingAmount()).isEqualByComparingTo("4500000");
    }
    @Test void fullRefundAndCancellationCreditRemainSeparate(){
        var obligations=List.of(obligation(DEPOSIT,"1000000","1000000","1000000","0",NOW),obligation(BALANCE,"4000000","0","0","0",NOW));
        var refund=new LedgerStore.Refund(UUID.randomUUID(),RECEIPT,DEPOSIT,n("1000000"),"EXECUTED","decision","payout");
        var result=RenewalStatusProjection.invoice(terms(),obligations,prepayment(),Set.of(RECEIPT),List.of(refund),BigDecimal.ZERO,NOW,true);
        assertThat(result.status()).isEqualTo("CANCELLED");assertThat(result.refundStatus()).isEqualTo("REFUNDED");
        assertThat(result.adjustmentAmount()).isEqualByComparingTo("5000000");assertThat(result.paidAmount()).isZero();
    }
    @Test void overdueFeeAndUnpaidBalanceUseTheirOwnDueDates(){
        var obligations=List.of(partial().getFirst(),obligation(BALANCE,"4000000","0","0","4000000",NOW.minusSeconds(1)));
        var result=RenewalStatusProjection.invoice(terms(),obligations,prepayment(),Set.of(RECEIPT),List.of(),n("50000"),NOW,false);
        assertThat(result.outstandingAmount()).isEqualByComparingTo("4050000");assertThat(result.overdueAmount()).isEqualByComparingTo("4050000");
        assertThat(result.totalAfterAdjustments()).isEqualByComparingTo("5050000");
    }
    @Test void reconciliationCannotAdvanceToSigningAndCompletedStateIsNotAPaymentResult(){
        var renewal=new Renewal();renewal.setStatus(RenewalStatus.accepted);
        var p=new RenewalStatusSummary.Transaction(UUID.randomUUID(),"tx","RENEWAL","RECONCILIATION_REQUIRED",n("1000000"),BigDecimal.ZERO,"VND",NOW.minusSeconds(1),true,false,NOW);
        assertThat(RenewalStatusProjection.workflow(renewal,null,null,List.of(p),NOW)).isEqualTo("AWAITING_RECONCILIATION");
        renewal.setStatus(RenewalStatus.completed);
        assertThat(RenewalStatusProjection.workflow(renewal,null,null,List.of(p),NOW)).isEqualTo("COMPLETED");
    }
}
