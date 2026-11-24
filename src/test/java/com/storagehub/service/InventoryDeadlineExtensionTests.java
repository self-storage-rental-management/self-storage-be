package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InventoryDeadlineExtensionTests {
    @Test
    @SuppressWarnings("unchecked")
    void scopedManagerExtendsDeadlineAndPendingTaskButCannotReuseStaleDeadline() {
        var facility = new Facility();
        var customer = new User();
        var reservation = new Reservation();
        var rental = new Rental();
        UUID facilityId = UUID.randomUUID();
        UUID rentalId = UUID.randomUUID();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(rental, "id", rentalId);
        rental.setFacility(facility);
        rental.setReservation(reservation);
        rental.setCustomer(customer);
        rental.setStatus(RentalStatus.setup);
        rental.setInventoryPending(true);
        rental.setMoveInCompletedAt(Instant.now().minus(Duration.ofDays(2)));
        Instant reminder = rental.getMoveInCompletedAt().plus(Duration.ofDays(4));
        Instant due = rental.getMoveInCompletedAt().plus(Duration.ofDays(7));
        Instant extended = due.plus(Duration.ofDays(1));
        rental.setInventoryDueAt(reminder);
        rental.setInventoryFinalDeadlineAt(due);
        var task = new MaintenanceTask();
        task.setDeadlineAt(due);

        var entityManager = mock(EntityManager.class);
        TypedQuery<MaintenanceTask> tasks = mock(TypedQuery.class, org.mockito.Mockito.RETURNS_SELF);
        when(entityManager.find(Rental.class, rentalId, LockModeType.PESSIMISTIC_WRITE)).thenReturn(rental);
        when(entityManager.createQuery(anyString(), eq(MaintenanceTask.class))).thenReturn(tasks);
        when(tasks.getResultList()).thenReturn(List.of(task));
        var audit = mock(AuditLogService.class);
        var workflow = new WorkflowSlaScheduler(entityManager, null, null, null, mock(NotificationService.class),
            audit, null, null, null, null);
        var manager = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), Set.of(),
            Map.of(facilityId, FacilityScopeLevel.MANAGE));
        var staff = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.STAFF), Set.of(),
            Map.of(facilityId, FacilityScopeLevel.OPERATE));

        assertThatThrownBy(() -> workflow.extendInventoryDeadline(staff, rentalId, "INVENTORY_CHECK", due, extended, "Cơ sở đổi lịch"))
            .hasMessageContaining("phạm vi quản lý");
        assertThatThrownBy(() -> workflow.extendInventoryDeadline(manager, rentalId, "INVENTORY_CHECK", due, extended, " "))
            .hasMessageContaining("lý do chậm do cơ sở");
        workflow.extendInventoryDeadline(manager, rentalId, "INVENTORY_CHECK", due, extended, "Cơ sở đổi lịch");

        assertThat(rental.getInventoryFinalDeadlineAt()).isEqualTo(extended);
        assertThat(rental.getInventoryDueAt()).isEqualTo(reminder);
        assertThat(task.getDeadlineAt()).isEqualTo(extended);
        assertThat(task.getDueAt()).isEqualTo(extended.atZone(BusinessTime.ZONE).toLocalDate());
        verify(audit).recordMutation("INVENTORY_DEADLINE_EXTENDED", "Rental", rentalId, facilityId,
            Map.of("dueAt", due), Map.of("dueAt", extended, "reason", "Cơ sở đổi lịch",
                "approvedBy", manager.userId(), "workflow", "INVENTORY_CHECK"));
        assertThatThrownBy(() -> workflow.extendInventoryDeadline(manager, rentalId, "INVENTORY_CHECK", due,
            extended.plus(Duration.ofDays(1)), "Yêu cầu cũ"))
            .hasMessageContaining("đã thay đổi");
    }
}
