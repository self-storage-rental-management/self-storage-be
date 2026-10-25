package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.api.payment.ManagerPaymentHistoryQuery;
import com.storagehub.api.renewal.RenewalStatusSummary;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.renewal.RenewalTransactionReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;

class ManagerRenewalPaymentHistoryTests {
    @Test void managerHistoryRetainsRawPaymentCodeButUsesVerifiedTransactionStatus(){
        var payments=mock(PaymentRepository.class);var returns=mock(ReturnCaseRepository.class);
        var service=new ManagerPaymentHistoryService(payments,returns,mock(AdminAuthorizationService.class),mock(FacilityScopeService.class));
        var reader=mock(RenewalTransactionReader.class);var beans=new StaticListableBeanFactory();beans.addBean("reader",reader);
        ReflectionTestUtils.setField(service,"renewalTransactions",beans.getBeanProvider(RenewalTransactionReader.class));
        var p=mock(Payment.class,RETURNS_DEEP_STUBS);var id=UUID.randomUUID();var renewalId=UUID.randomUUID();var now=Instant.now();
        when(p.getId()).thenReturn(id);when(p.getReservation()).thenReturn(null);when(p.getRenewal().getId()).thenReturn(renewalId);
        when(p.getRenewal().getRental().getCustomer().getFullName()).thenReturn("Customer");
        when(p.getPurpose()).thenReturn(PaymentType.RENEWAL);when(p.getStatus()).thenReturn(PaymentStatus.PAID);
        when(p.getGatewayProvider()).thenReturn("VNPAY");when(p.getGatewayIntentId()).thenReturn("intent");
        when(p.getAmount()).thenReturn(new BigDecimal("100"));when(p.getCurrency()).thenReturn("VND");when(p.getPaidAt()).thenReturn(now);
        when(reader.read(eq(p),any())).thenReturn(new RenewalStatusSummary.Transaction(id,"intent","RENEWAL","RECONCILIATION_REQUIRED",
            new BigDecimal("100"),BigDecimal.ZERO,"VND",now,false,false,now));
        when(payments.searchFacilityPaymentHistory(any(),any(),any())).thenReturn(new PageImpl<>(List.of(p)));
        when(returns.searchFacilityPaymentSettlements(any(),any(),any())).thenReturn(Page.empty());
        var actor=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(),Map.of());
        var result=service.list(actor,new ManagerPaymentHistoryQuery(0,10,UUID.randomUUID(),""),"test").data().getFirst();
        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.transactionStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.receiptAvailable()).isFalse();
    }
}
