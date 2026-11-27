package com.storagehub.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.storagehub.api.support.SupportResponse;
import com.storagehub.common.api.SortedQueuePage;
import com.storagehub.domain.model.SupportTicketStatus;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SupportQueueOrderTests {
    @Test void authoritativeDeadlineOutranksCreationTimeAcrossPagesAndMissingSlaIsLast() {
        var fresh=row(1,SupportTicketStatus.open,"2026-11-26T01:00:00Z","2026-11-26T03:00:00Z");
        var old=row(2,SupportTicketStatus.open,"2026-11-20T01:00:00Z","2026-11-26T05:00:00Z");
        var missing=row(3,SupportTicketStatus.open,"2026-11-19T01:00:00Z",null);
        var page=new SortedQueuePage<SupportResponse>(0,1,SupportQueueOrder.order(SupportService.Audience.STAFF));
        List.of(old,missing,fresh).forEach(page::accept);
        assertThat(page.rows()).containsExactly(fresh);
        assertThat(page.total()).isEqualTo(3);
    }
    @Test void eachRoleSeesItsOwnTurnBeforeWaitingOnAnotherRole() {
        var customer=row(1,SupportTicketStatus.waiting_customer,"2026-11-20T01:00:00Z",null);
        var staff=row(2,SupportTicketStatus.open,"2026-11-26T01:00:00Z",null);
        assertThat(SupportQueueOrder.order(SupportService.Audience.STAFF).compare(staff,customer)).isNegative();
        assertThat(SupportQueueOrder.order(SupportService.Audience.CUSTOMER).compare(customer,staff)).isNegative();
    }
    private SupportResponse row(int id,SupportTicketStatus status,String created,String due) {
        var row=mock(SupportResponse.class);
        when(row.id()).thenReturn(new UUID(0,id)); when(row.status()).thenReturn(status);
        when(row.createdAt()).thenReturn(Instant.parse(created));
        if(due!=null)when(row.sla()).thenReturn(new SupportSources.Sla("MEDIUM","test","v1",Instant.parse(due),null,false));
        return row;
    }
}
