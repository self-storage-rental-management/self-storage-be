package com.storagehub.service.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.api.renewal.RenewalOperationResponse;
import com.storagehub.domain.model.Renewal;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.renewal.operations.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RenewalSigningSlotsReadServiceTests {
    private final EntityManager em=mock(EntityManager.class);
    private final RenewalOperationService operations=mock(RenewalOperationService.class);
    private final RenewalOperationSources.CalendarSource calendar=mock(RenewalOperationSources.CalendarSource.class);
    private final RenewalOperationResponse detail=mock(RenewalOperationResponse.class);
    private final Renewal n=mock(Renewal.class);
    private final UUID id=UUID.randomUUID();
    private final ActorPrincipal actor=mock(ActorPrincipal.class);
    private final LocalDate date=LocalDate.of(2026,10,14);
    private final Instant now=Instant.parse("2026-10-14T00:00:00Z"),deadline=Instant.parse("2026-10-14T09:45:00Z");
    private RenewalSigningSlotsReadService service;
    @BeforeEach void setUp() {
        var beans=new StaticListableBeanFactory();beans.addBean("calendar",calendar);beans.addBean("clock",Clock.fixed(now,ZoneOffset.UTC));
        service=new RenewalSigningSlotsReadService(em,operations,beans.getBeanProvider(RenewalOperationSources.CalendarSource.class),beans.getBeanProvider(Clock.class));
        when(operations.detail(actor,id,RenewalOperationService.Audience.CUSTOMER)).thenReturn(detail);
        when(detail.phase()).thenReturn("SIGNING");when(detail.effectiveSigningDeadline()).thenReturn(deadline);when(detail.recoveryCutoff()).thenReturn(deadline);
        when(calendar.atomic()).thenReturn(true);when(calendar.feasible(n,now,deadline)).thenReturn(true);when(em.find(Renewal.class,id)).thenReturn(n);
        when(calendar.slot(eq(n),any())).thenAnswer(i->{Instant at=i.getArgument(1);return Optional.of(new RenewalOperationSources.Slot("slot",at,at.plusSeconds(1800)));});
    }
    @Test void returnsOnlyWholeSlotsBeforeSigningDeadline() {
        var result=service.read(actor,id,date,RenewalOperationService.Audience.CUSTOMER);
        assertThat(result.slots()).hasSize(17);
        assertThat(result.slots().getLast().start()).isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
    }
    @Test void missingAtomicCalendarDoesNotProduceAvailableTimes() {
        when(calendar.atomic()).thenReturn(false);
        assertThatThrownBy(()->service.read(actor,id,date,RenewalOperationService.Audience.CUSTOMER)).hasMessageContaining("DEFERRED_SOURCE");
    }
    @Test void selectionCannotContinueAfterArrival() {
        when(detail.arrivalRef()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(()->service.read(actor,id,date,RenewalOperationService.Audience.CUSTOMER)).hasMessageContaining("not available");
        verifyNoInteractions(em);
    }
    @Test void malformedCalendarEvidenceIsRejected() {
        when(calendar.slot(eq(n),any())).thenReturn(Optional.of(new RenewalOperationSources.Slot("slot",now,now.plusSeconds(1800))));
        assertThatThrownBy(()->service.read(actor,id,date,RenewalOperationService.Audience.CUSTOMER)).hasMessageContaining("inconsistent data");
    }
}
