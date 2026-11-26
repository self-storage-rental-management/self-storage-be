package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MismatchUnitReleaseTests {
    @Test
    void releaseChecksPhysicalConditionsWithoutWaitingForFinancialSettlement() {
        var tasks = mock(MaintenanceTaskRepository.class);
        var assignments = mock(UnitAssignmentRepository.class);
        var rentals = mock(RentalRepository.class);
        var credentials = mock(AccessCredentialRepository.class);
        var policy = new MismatchUnitRelease(tasks, assignments, rentals, credentials, mock(WorkflowBusinessCalendar.class));
        var unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "id", UUID.randomUUID());
        unit.setStatus(StorageUnitStatus.maintenance);
        var facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        unit.setFacility(facility);
        var reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        var rental = new Rental();
        ReflectionTestUtils.setField(rental, "id", UUID.randomUUID());
        rental.setReservation(reservation);
        rental.setStorageUnit(unit);
        rental.setActualReturnedAt(Instant.now());
        var rc = new ReturnCase();
        rc.setRental(rental);
        rc.setStorageUnit(unit);
        rc.setFacility(facility);
        rc.setReturnType(ReturnType.INVENTORY_MISMATCH);
        rc.setStatus(ReturnCaseStatus.disputed);
        rc.setInspectedAt(Instant.now());
        var task = new MaintenanceTask();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        task.setReturnCase(rc);
        var assignment = new UnitAssignment();
        assignment.setStorageUnit(unit);
        assignment.setStatus(UnitAssignmentStatus.ACTIVE);
        when(assignments.findByReservation_IdAndStatus(reservation.getId(), UnitAssignmentStatus.ACTIVE)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> policy.verifyReady(task, unit)).hasMessageContaining("gian trống");
        rc.setWarehouseEmptyVerified(true);
        when(credentials.existsByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.ACTIVE)).thenReturn(true, false, false);
        assertThatThrownBy(() -> policy.verifyReady(task, unit)).hasMessageContaining("quyền truy cập");
        when(rentals.existsOtherUnreturnedRental(unit.getId(), rental.getId())).thenReturn(true, false);
        assertThatThrownBy(() -> policy.verifyReady(task, unit)).hasMessageContaining("hồ sơ thuê khác");
        policy.verifyReady(task, unit);

        assertThat(assignment.getStatus()).isEqualTo(UnitAssignmentStatus.COMPLETED);
        assertThat(rc.getStatus()).isEqualTo(ReturnCaseStatus.disputed);
    }
}
