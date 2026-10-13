package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.RentalQueryService;
import com.storagehub.service.overdue.*;
import com.storagehub.service.renewal.integration.RenewalPolicyPublicationService;
import com.storagehub.service.renewal.operations.RenewalOperationService;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

class RenewalRiskReadServiceTests {
    private final EntityManager em=mock(EntityManager.class);
    private final RentalQueryService rentals=mock(RentalQueryService.class);
    private final OverdueSources.TermSource terms=mock(OverdueSources.TermSource.class);
    private final RecoveryTaskReadService tasks=mock(RecoveryTaskReadService.class);
    private final UUID id=UUID.randomUUID(),facility=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-18T01:00:00Z");
    private final ActorPrincipal actor=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
    private final Rental rental=new Rental();

    private RenewalRiskReadService service() {
        ReflectionTestUtils.setField(rental,"id",id);var f=new Facility();ReflectionTestUtils.setField(f,"id",facility);rental.setFacility(f);rental.setStatus(RentalStatus.active);
        when(em.find(Rental.class,id)).thenReturn(rental);
        var beans=new StaticListableBeanFactory();beans.addBean("terms",terms);beans.addBean("tasks",tasks);
        beans.addBean("clock",Clock.fixed(now,ZoneOffset.UTC));
        return new RenewalRiskReadService(em,rentals,mock(RenewalOperationService.class),
            beans.getBeanProvider(OverdueSources.TermSource.class),beans.getBeanProvider(Clock.class),new ObjectMapper(),
            mock(RenewalPolicyPublicationService.class),beans.getBeanProvider(RecoveryTaskReadService.class));
    }
    @Test void dayEightAloneDoesNotClaimThatRecoveryStarted() {
        var service=service();
        when(terms.term(rental,now)).thenReturn(Optional.of(new OverdueSources.Term("policy","1",LocalDate.of(2026,10,10),3,6,7,8,
            Instant.parse("2026-10-17T10:00:00Z"),Instant.parse("2026-10-17T17:00:00Z"))));
        when(tasks.forRental(id)).thenReturn(Optional.empty());
        var view=service.rental(actor,id,false);
        assertThat(view.overdueDays()).isEqualTo(8);
        assertThat(view.repossessionStatus()).isEqualTo("NOT_CONFIRMED");
        assertThat(view.repossessionStartedAt()).isNull();assertThat(view.recoveryTaskId()).isNull();
        assertThat(view.withinOrdinaryRenewalWindow()).isFalse();
        verify(rentals).detail(actor,id,false);
    }
    @Test void receiptProvesRecoveryEvenIfCurrentTermSourceIsUnavailable() {
        var service=service();var taskId=UUID.randomUUID();
        when(tasks.forRental(id)).thenReturn(Optional.of(new RecoveryTaskReadService.View(taskId,"RENTAL_TERM:"+id,id,facility,"OPEN","AUTO_OVERDUE_DAY_8",now.minusSeconds(60),"Customer","UNIT")));
        var view=service.rental(actor,id,false);
        assertThat(view.completeness()).isEqualTo("UNKNOWN");
        assertThat(view.repossessionStatus()).isEqualTo("REPOSSESSION_STARTED");
        assertThat(view.repossessionStartedAt()).isEqualTo(now.minusSeconds(60));
        assertThat(view.recoveryTaskId()).isEqualTo(taskId);
        assertThat(view.withinOrdinaryRenewalWindow()).isFalse();
    }
    @Test void mismatchedFacilityReceiptCannotBecomeCustomerEvidence() {
        var service=service();
        when(tasks.forRental(id)).thenReturn(Optional.of(new RecoveryTaskReadService.View(UUID.randomUUID(),"RENTAL_TERM:"+id,id,UUID.randomUUID(),"OPEN","reason",now,"Customer","UNIT")));
        assertThatThrownBy(()->service.rental(actor,id,false)).hasMessageContaining("facility mismatch");
    }
}
