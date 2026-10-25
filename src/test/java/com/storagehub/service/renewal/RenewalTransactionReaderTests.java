package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.ledger.LedgerStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RenewalTransactionReaderTests {
    private static <T extends BaseEntity> T identified(T entity){org.springframework.test.util.ReflectionTestUtils.setField(entity,"id",UUID.randomUUID());return entity;}
    @Test void callbackProofAndMatchingLedgerAllocationAreBothRequired(){
        var now=Instant.parse("2026-10-25T08:00:00Z");
        var customer=identified(new User());var facility=identified(new Facility());
        var rental=identified(new Rental());rental.setCustomer(customer);rental.setFacility(facility);
        var renewal=identified(new Renewal());renewal.setRental(rental);
        var payment=identified(new Payment());payment.setRenewal(renewal);payment.setPurpose(PaymentType.RENEWAL);
        payment.setStatus(PaymentStatus.PAID);payment.setAmount(new BigDecimal("100"));payment.setCurrency("VND");payment.setPaidAt(now);
        payment.setRenewalAllocatedAt(now);payment.setRenewalLedgerReceiptRef(UUID.randomUUID());
        var proof=mock(PaymentProvenance.class);
        var reader=new RenewalTransactionReader(new StaticListableBeanFactory().getBeanProvider(LedgerStore.class),proof);
        when(proof.classify(payment)).thenReturn(PaymentProvenance.Classification.SANDBOX);
        assertThat(reader.project(payment,Optional.empty(),now).status()).isEqualTo("RECONCILIATION_REQUIRED");
        var receipt=new LedgerStore.Receipt(payment.getRenewalLedgerReceiptRef(),LedgerStore.Method.VNPAY_SANDBOX,payment.getAmount(),"payment:"+payment.getId(),null,now);
        var account=new LedgerStore.Snapshot(rental.getId(),customer.getId(),facility.getId(),1,List.of(),List.of(receipt),List.of());
        assertThat(reader.project(payment,Optional.of(account),now).status()).isEqualTo("SUCCEEDED");
        when(proof.classify(payment)).thenReturn(PaymentProvenance.Classification.UNVERIFIED);
        assertThat(reader.project(payment,Optional.of(account),now).receiptAvailable()).isFalse();
        assertThat(reader.project(payment,Optional.of(account),now).status()).isEqualTo("RECONCILIATION_REQUIRED");
    }
}
