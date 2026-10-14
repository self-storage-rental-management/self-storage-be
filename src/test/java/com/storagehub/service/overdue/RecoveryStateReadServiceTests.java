package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RecoveryStateReadServiceTests {
    private final EntityManager em=mock(EntityManager.class);
    private final DuongResourceAccess access=mock(DuongResourceAccess.class);
    private final UUID id=UUID.randomUUID(),facility=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-13T01:00:00Z");
    private final ActorPrincipal actor=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.STAFF),Set.of(),Map.of());
    private Rental rental;
    private RecoveryStateReadService service(OverdueSources.RecoverySource source) {
        rental=mock(Rental.class);var f=mock(Facility.class);
        when(em.find(Rental.class,id)).thenReturn(rental);when(rental.getFacility()).thenReturn(f);when(f.getId()).thenReturn(facility);
        var beans=new StaticListableBeanFactory();if(source!=null)beans.addBean("source",source);
        beans.addBean("clock",Clock.fixed(now,ZoneOffset.UTC));
        return new RecoveryStateReadService(em,access,beans.getBeanProvider(OverdueSources.RecoverySource.class),beans.getBeanProvider(Clock.class));
    }
    @Test void absenceOfAReceiverIsUnknownNotClearRecovery() {
        var view=service(null).read(actor,id);
        assertThat(view.sourceCoverage()).isEqualTo("UNKNOWN");assertThat(view.recoveryBlocked()).isNull();
    }
    @Test void missingReceiverEvidenceIsUnknown() {
        var source=mock(OverdueSources.RecoverySource.class);var service=service(source);
        when(source.state(rental,now)).thenReturn(Optional.empty());
        assertThat(service.read(actor,id).recoveryBlocked()).isNull();
    }
    @Test void staleOrCrossResourceEvidenceIsRejected() {
        var source=mock(OverdueSources.RecoverySource.class);var service=service(source);
        when(source.state(rental,now)).thenReturn(Optional.of(new OverdueSources.RecoveryState(UUID.randomUUID(),facility,now,false)));
        assertThatThrownBy(()->service.read(actor,id)).hasMessageContaining("another rental");
        when(source.state(rental,now)).thenReturn(Optional.of(new OverdueSources.RecoveryState(id,facility,now.minusSeconds(1),false)));
        assertThatThrownBy(()->service.read(actor,id)).hasMessageContaining("stale");
    }
    @Test void currentReceiverStateKeepsFacilityScopeAuthorization() {
        var source=mock(OverdueSources.RecoverySource.class);var service=service(source);
        when(source.state(rental,now)).thenReturn(Optional.of(new OverdueSources.RecoveryState(id,facility,now,true)));
        var view=service.read(actor,id);
        assertThat(view.sourceCoverage()).isEqualTo("VERIFIED");assertThat(view.recoveryBlocked()).isTrue();
        verify(access).require(actor,RoleCode.STAFF,facility,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
    }
}
