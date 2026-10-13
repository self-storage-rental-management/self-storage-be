package com.storagehub.service.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.integration.DuongResourceAccess;
import com.storagehub.service.renewal.integration.RenewalAssignmentSource;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class StaffRenewalSigningCalendarSourceTests {
    private final RenewalCalendarStore store=mock(RenewalCalendarStore.class);
    private final EntityManager em=mock(EntityManager.class);
    private final RenewalAssignmentSource assignments=mock(RenewalAssignmentSource.class);
    private final StaffRenewalSigningCalendarSource source=new StaffRenewalSigningCalendarSource(store,em,mock(DuongResourceAccess.class),assignments);
    private final UUID id=UUID.randomUUID(),facilityId=UUID.randomUUID(),staff=UUID.randomUUID(),quote=UUID.randomUUID();
    private final Instant at=Instant.parse("2026-10-14T01:00:00Z");
    private Renewal renewal() {
        var n=mock(Renewal.class);var r=mock(Rental.class);var f=mock(Facility.class);
        when(n.getId()).thenReturn(id);when(n.getRental()).thenReturn(r);when(r.getFacility()).thenReturn(f);when(f.getId()).thenReturn(facilityId);
        when(assignments.current(n)).thenReturn(Optional.of(new RenewalAssignmentSource.Assignment(id,facilityId,staff,quote,1)));
        when(store.facilityActive(facilityId)).thenReturn(true);return n;
    }
    @Test void capacityIsBoundToCurrentStaffAndReferenceDoesNotExposeStaffId() {
        var n=renewal();var slot=source.slot(n,at).orElseThrow();
        assertThat(slot.end()).isEqualTo(at.plusSeconds(1800));
        assertThat(slot.reference()).doesNotContain(staff.toString());
        when(store.busy(staff,at,at.plusSeconds(1800),id)).thenReturn(List.of(new RenewalCalendarStore.Interval(at,at.plusSeconds(1800))));
        assertThat(source.slot(n,at)).isEmpty();
    }
    @Test void reservationRechecksConflictsAfterAcquiringStaffLock() {
        var n=renewal();var slot=source.slot(n,at).orElseThrow();
        when(store.busy(staff,at,slot.end(),id)).thenReturn(List.of(new RenewalCalendarStore.Interval(at,slot.end())));
        assertThatThrownBy(()->source.reserve(n,slot,at.minusSeconds(1))).hasMessageContaining("was taken");
        var order=inOrder(store);order.verify(store).lockFacility(facilityId);order.verify(store).lockStaff(staff);
        verify(store,never()).reserve(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void missingAssignmentCannotBeReplacedByTheStaffRole() {
        var n=renewal();when(assignments.current(n)).thenReturn(Optional.empty());
        assertThatThrownBy(()->source.slot(n,at)).hasMessageContaining("current signing Staff assignment");
    }
    @Test void replacingStaffCannotOrphanAReservedAppointment() {
        var n=renewal();when(store.current(id,true)).thenReturn(Optional.of(new RenewalCalendarStore.Booking(id,staff,quote,at,at.plusSeconds(1800),"RESERVED")));
        assertThatThrownBy(()->source.beforeStaffAssignment(n,UUID.randomUUID())).hasMessageContaining("reconciled");
    }
    @Test void missingHistoricalCoverageReturnsUnknownBeforeCheckingFeasibility() {
        var n=renewal();when(store.legacyAppointmentsUncovered(at)).thenReturn(true);
        assertThat(source.feasible(n.getRental(),at,at.plusSeconds(3600))).isEmpty();
    }
}
