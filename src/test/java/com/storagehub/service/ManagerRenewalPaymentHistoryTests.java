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
    @Test void directPaymentLinkChecksFacilityScopeBeforeReturningTheExactPayment() {
        var payments = mock(PaymentRepository.class);
        var scopes = mock(FacilityScopeService.class);
        var authorization = mock(AdminAuthorizationService.class);
        var service = new ManagerPaymentHistoryService(payments, mock(ReturnCaseRepository.class), authorization, scopes);
        var actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), Set.of(), Map.of());
        var id = UUID.randomUUID();
        var facilityId = UUID.randomUUID();
        var payment = mock(Payment.class, RETURNS_DEEP_STUBS);
        when(payments.findById(id)).thenReturn(Optional.of(payment));
        when(payment.getId()).thenReturn(id);
        when(payment.getReservation().getFacility().getId()).thenReturn(facilityId);
        when(payment.getReservation().getCustomer().getFullName()).thenReturn("Demo Customer");
        when(payment.getPurpose()).thenReturn(PaymentType.MONTHLY_RENT);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(payment.getAmount()).thenReturn(new BigDecimal("100"));
        when(payment.getCurrency()).thenReturn("VND");
        when(payment.getCreatedAt()).thenReturn(Instant.now());
        doThrow(com.storagehub.common.api.ApiExceptions.forbidden("Outside facility"))
            .doNothing().when(scopes).assertCanRead(actor, facilityId);
        assertThatThrownBy(() -> service.detail(actor, id)).hasMessageContaining("Outside facility");
        var result = service.detail(actor, id);
        assertThat(result.id()).isEqualTo(id.toString());
        assertThat(result.status()).isEqualTo("PENDING");
        verify(authorization, times(2)).require(actor, SystemPermission.VIEW_PAYMENTS);
    }

    @Test void searchUsesReturnReceiptCodesWithinTheFacilityQuery() {
        var payments = mock(PaymentRepository.class);
        var returns = mock(ReturnCaseRepository.class);
        var service = new ManagerPaymentHistoryService(payments, returns, mock(AdminAuthorizationService.class), mock(FacilityScopeService.class));
        var codes = mock(MoneyReceiptCodes.class);
        ReflectionTestUtils.setField(service, "receiptCodes", codes);
        var caseId = UUID.randomUUID();
        var facilityId = UUID.randomUUID();
        when(codes.findSourcesMatching("RETURN", "%pc-123%")).thenReturn(List.of(caseId));
        when(payments.searchFacilityPaymentHistory(eq(facilityId), eq("%pc-123%"), any())).thenReturn(Page.empty());
        when(returns.searchFacilityPaymentSettlements(eq(facilityId), eq("%pc-123%"), eq(List.of(caseId)), any())).thenReturn(Page.empty());
        var actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), Set.of(), Map.of());
        service.list(actor, new ManagerPaymentHistoryQuery(0, 10, facilityId, "PC-123"), "test");
        verify(returns).searchFacilityPaymentSettlements(eq(facilityId), eq("%pc-123%"), eq(List.of(caseId)), any());
    }

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
        when(p.getGatewayTransactionNo()).thenReturn("gateway-number");
        when(p.getReceiptCode()).thenReturn("PT-ABCD-EFGH-1234");
        when(p.getAmount()).thenReturn(new BigDecimal("100"));when(p.getCurrency()).thenReturn("VND");when(p.getPaidAt()).thenReturn(now);
        when(reader.read(eq(p),any())).thenReturn(new RenewalStatusSummary.Transaction(id,"intent","RENEWAL","RECONCILIATION_REQUIRED",
            new BigDecimal("100"),BigDecimal.ZERO,"VND",now,false,false,now));
        when(payments.searchFacilityPaymentHistory(any(),any(),any())).thenReturn(new PageImpl<>(List.of(p)));
        when(returns.searchFacilityPaymentSettlements(any(),any(),any(),any())).thenReturn(Page.empty());
        var actor=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(),Map.of());
        var result=service.list(actor,new ManagerPaymentHistoryQuery(0,10,UUID.randomUUID(),""),"test").data().getFirst();
        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.receiptCode()).isEqualTo("PT-ABCD-EFGH-1234");
        assertThat(result.transactionReference()).isEqualTo("intent");
        assertThat(result.gatewayTransactionNo()).isEqualTo("gateway-number");
        assertThat(result.transactionStatus()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.receiptAvailable()).isFalse();
    }
}
