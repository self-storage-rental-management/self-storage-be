package com.storagehub.service.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.common.api.ApiExceptions;
import jakarta.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SupportAutoCloseCoordinatorTests {
    @Test @SuppressWarnings("unchecked") void boundedScanContinuesAfterDeferredTicketAndUsesKeyset(){
        var em=mock(EntityManager.class);var transactions=mock(PlatformTransactionManager.class);var service=mock(SupportService.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->new SimpleTransactionStatus());
        TypedQuery<UUID> query=mock(TypedQuery.class);when(em.createQuery(anyString(),eq(UUID.class))).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);when(query.setMaxResults(2)).thenReturn(query);
        var first=UUID.randomUUID();var second=UUID.randomUUID();when(query.getResultList()).thenReturn(List.of(first,second),List.of());
        when(service.autoClose(first)).thenThrow(ApiExceptions.conflict("DEFERRED_SOURCE: test-only missing policy"));
        var job=new SupportAutoCloseCoordinator(em,transactions,service,2);job.scan();job.scan();
        verify(service).autoClose(first);verify(service).autoClose(second);verify(query).setParameter("cursor",second);
        verify(query,times(2)).setMaxResults(2);verify(transactions,times(2)).commit(any());
    }
    @Test void invalidBatchSizesCannotEnableUnboundedJob(){
        for(int size:new int[]{0,501})assertThatThrownBy(()->new SupportAutoCloseCoordinator(mock(EntityManager.class),mock(PlatformTransactionManager.class),mock(SupportService.class),size)).isInstanceOf(IllegalArgumentException.class);
    }
}
