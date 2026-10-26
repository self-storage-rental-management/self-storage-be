package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RenewalTransactionStatusTests {
    private static final Instant NOW=Instant.parse("2026-10-25T08:00:00Z");
    private Payment payment(PaymentStatus status){var p=new Payment();p.setStatus(status);p.setAmount(new BigDecimal("100"));p.setPaymentDeadline(NOW.plusSeconds(60));return p;}
    @Test void paidWithoutVerifiedAllocationRemainsInReconciliation(){
        assertThat(RenewalTransactionStatus.resolve(payment(PaymentStatus.PAID),false,BigDecimal.ZERO,NOW)).isEqualTo("RECONCILIATION_REQUIRED");
    }
    @Test void pendingAfterDeadlineIsNotAssumedFailed(){
        var p=payment(PaymentStatus.PENDING);p.setPaymentDeadline(NOW.minusSeconds(1));
        assertThat(RenewalTransactionStatus.resolve(p,false,BigDecimal.ZERO,NOW)).isEqualTo("RECONCILIATION_REQUIRED");
    }
    @Test void oldFailureIsNotRenamedExpiredJustBecauseTimePassed(){
        var p=payment(PaymentStatus.FAILED);p.setFailureCode("24");p.setLastReconciledAt(NOW);p.setPaymentDeadline(NOW.minusSeconds(1));
        assertThat(RenewalTransactionStatus.resolve(p,false,BigDecimal.ZERO,NOW)).isEqualTo("FAILED");
        p.setFailureCode("91");
        assertThat(RenewalTransactionStatus.resolve(p,false,BigDecimal.ZERO,NOW)).isEqualTo("EXPIRED");
    }
    @Test void confirmedRefundsAreIndependentFromInvoiceSettlement(){
        var p=payment(PaymentStatus.PAID);
        assertThat(RenewalTransactionStatus.resolve(p,true,new BigDecimal("40"),NOW)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(RenewalTransactionStatus.resolve(p,true,new BigDecimal("100"),NOW)).isEqualTo("REFUNDED");
        assertThat(RenewalTransactionStatus.resolve(p,true,BigDecimal.ZERO,NOW)).isEqualTo("SUCCEEDED");
    }
    @Test void unreconciledRefundMetadataCannotCertifyARefund(){
        var p=payment(PaymentStatus.PAID);p.setRefundedAmount(new BigDecimal("40"));
        assertThat(RenewalTransactionStatus.resolve(p,true,BigDecimal.ZERO,NOW)).isEqualTo("RECONCILIATION_REQUIRED");
    }
}
