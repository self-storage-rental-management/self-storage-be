package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.maintenance.AssignStaffTaskRequest;
import com.storagehub.api.maintenance.CompleteMaintenanceTaskRequest;
import com.storagehub.api.maintenance.CreateMaintenanceTaskRequest;
import com.storagehub.api.maintenance.MaintenanceTaskResponse;
import com.storagehub.api.maintenance.UpdateStorageUnitStatusRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MaintenanceServiceTests {

    @Mock MaintenanceTaskRepository maintenanceTaskRepository;
    @Mock StorageUnitRepository storageUnitRepository;
    @Mock ReturnCaseRepository returnCaseRepository;
    @Mock UserRepository userRepository;
    @Mock UserFacilityScopeRepository userFacilityScopeRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock FacilityScopeService facilityScopeService;
    @Mock NotificationService notificationService;
    @Mock AuditLogService auditLogService;

    private ObjectMapper objectMapper;
    private MaintenanceService maintenanceService;

    private ActorPrincipal managerActor;
    private ActorPrincipal staffActor;
    private Facility facility;
    private StorageUnit unit;
    private User managerUser;
    private User staffUser;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        maintenanceService = new MaintenanceService(
            maintenanceTaskRepository,
            storageUnitRepository,
            returnCaseRepository,
            userRepository,
            userFacilityScopeRepository,
            authorizationService,
            facilityScopeService,
            notificationService,
            auditLogService,
            objectMapper
        );

        UUID facilityId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();

        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        facility.setName("Kho Quận 1");

        managerActor = new ActorPrincipal(
            managerId,
            UUID.randomUUID(),
            Set.of(RoleCode.MANAGER),
            Set.of("inventory:update", "staff_tasks:update", "storage_units:read"),
            Map.of(facilityId, com.storagehub.domain.model.FacilityScopeLevel.MANAGE)
        );

        staffActor = new ActorPrincipal(
            staffId,
            UUID.randomUUID(),
            Set.of(RoleCode.STAFF),
            Set.of("storage_units:read"),
            Map.of(facilityId, com.storagehub.domain.model.FacilityScopeLevel.OPERATE)
        );

        unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "id", UUID.randomUUID());
        unit.setCode("U-101");
        unit.setFacility(facility);
        unit.setStatus(StorageUnitStatus.available);

        managerUser = new User();
        ReflectionTestUtils.setField(managerUser, "id", managerId);
        managerUser.setFullName("Nguyen Van Manager");
        managerUser.setEmail("manager@storagehub.vn");

        staffUser = new User();
        ReflectionTestUtils.setField(staffUser, "id", staffId);
        staffUser.setFullName("Tran Van Staff");
        staffUser.setEmail("staff@storagehub.vn");
    }

    @Test
    void createTask_success() {
        CreateMaintenanceTaskRequest req = new CreateMaintenanceTaskRequest(
            unit.getId(),
            "Bảo dưỡng định kỳ",
            "Kiểm tra cửa và hệ thống thông gió",
            TaskPriority.high,
            DamageClassification.no_damage,
            staffUser.getId(),
            LocalDate.now().plusDays(2),
            null
        );

        when(storageUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        when(userRepository.findById(managerActor.userId())).thenReturn(Optional.of(managerUser));
        when(userRepository.findById(staffUser.getId())).thenReturn(Optional.of(staffUser));
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> {
            MaintenanceTask t = invocation.getArgument(0);
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(t, "createdAt", Instant.now());
            return t;
        });

        MaintenanceTaskResponse response = maintenanceService.createTask(managerActor, req);

        assertThat(response).isNotNull();
        assertThat(response.title()).isEqualTo("Bảo dưỡng định kỳ");
        assertThat(response.priority()).isEqualTo(TaskPriority.high);
        assertThat(response.status()).isEqualTo(MaintenanceTaskStatus.open);
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.maintenance);

        verify(authorizationService).require(managerActor, SystemPermission.MANAGE_INVENTORY);
        verify(facilityScopeService).assertCanManage(managerActor, facility.getId());
        verify(notificationService).createNotification(
            eq(staffUser.getId()),
            eq(NotificationType.SYSTEM),
            any(),
            any(),
            any()
        );
    }

    @Test
    void updateUnitStatus_toMaintenance_createsTask() {
        UpdateStorageUnitStatusRequest req = new UpdateStorageUnitStatusRequest(
            StorageUnitStatus.maintenance,
            "Cần sửa chữa bản lề cửa",
            true,
            staffUser.getId(),
            TaskPriority.medium,
            DamageClassification.minor_damage,
            LocalDate.now().plusDays(1)
        );

        when(storageUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        when(userRepository.findById(managerActor.userId())).thenReturn(Optional.of(managerUser));
        when(userRepository.findById(staffUser.getId())).thenReturn(Optional.of(staffUser));
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> {
            MaintenanceTask t = invocation.getArgument(0);
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });

        MaintenanceTaskResponse response = maintenanceService.updateUnitStatus(managerActor, unit.getId(), req);

        assertThat(response).isNotNull();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.maintenance);
        assertThat(response.title()).isEqualTo("Bảo trì gian U-101");
        assertThat(response.damageClassification()).isEqualTo(DamageClassification.minor_damage);
        verify(storageUnitRepository).save(unit);
    }

    @Test
    void updateUnitStatus_toAvailable_completesActiveTasks() {
        unit.setStatus(StorageUnitStatus.maintenance);

        MaintenanceTask openTask = new MaintenanceTask();
        ReflectionTestUtils.setField(openTask, "id", UUID.randomUUID());
        openTask.setFacility(facility);
        openTask.setStorageUnit(unit);
        openTask.setStatus(MaintenanceTaskStatus.open);

        UpdateStorageUnitStatusRequest req = new UpdateStorageUnitStatusRequest(
            StorageUnitStatus.available,
            "Nghiệm thu hoàn tất",
            false,
            null,
            null,
            null,
            null
        );

        when(storageUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        when(maintenanceTaskRepository.findByStorageUnit_IdAndStatusIn(eq(unit.getId()), any()))
            .thenReturn(List.of(openTask));

        MaintenanceTaskResponse response = maintenanceService.updateUnitStatus(managerActor, unit.getId(), req);

        assertThat(response).isNotNull();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.available);
        assertThat(openTask.getStatus()).isEqualTo(MaintenanceTaskStatus.completed);
        assertThat(openTask.getCompletedAt()).isNotNull();
        assertThat(openTask.getResultReport()).isEqualTo("Nghiệm thu hoàn tất");
        verify(maintenanceTaskRepository).save(openTask);
    }

    @Test
    void updateUnitStatus_cannotSetOccupiedUnitToMaintenance() {
        unit.setStatus(StorageUnitStatus.occupied);

        UpdateStorageUnitStatusRequest req = new UpdateStorageUnitStatusRequest(
            StorageUnitStatus.maintenance,
            "Cần kiểm tra",
            true,
            null,
            null,
            null,
            null
        );

        when(storageUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));

        assertThatThrownBy(() -> maintenanceService.updateUnitStatus(managerActor, unit.getId(), req))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Cannot place an actively occupied storage unit into maintenance");
    }

    @Test
    void assignStaff_success() {
        MaintenanceTask task = new MaintenanceTask();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        task.setFacility(facility);
        task.setStorageUnit(unit);
        task.setTitle("Bảo trì khóa gian");
        task.setStatus(MaintenanceTaskStatus.open);

        AssignStaffTaskRequest req = new AssignStaffTaskRequest(staffUser.getId());

        when(maintenanceTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(userRepository.findById(staffUser.getId())).thenReturn(Optional.of(staffUser));
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MaintenanceTaskResponse response = maintenanceService.assignStaff(managerActor, task.getId(), req);

        assertThat(response).isNotNull();
        assertThat(task.getAssignedTo()).isEqualTo(staffUser);
        verify(notificationService).createNotification(
            eq(staffUser.getId()),
            eq(NotificationType.SYSTEM),
            any(),
            any(),
            any()
        );
    }

    @Test
    void startTask_success() {
        MaintenanceTask task = new MaintenanceTask();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        task.setFacility(facility);
        task.setStorageUnit(unit);
        task.setTitle("Bảo trì hệ thống quạt");
        task.setStatus(MaintenanceTaskStatus.open);

        when(maintenanceTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(userRepository.findById(staffActor.userId())).thenReturn(Optional.of(staffUser));
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MaintenanceTaskResponse response = maintenanceService.startTask(staffActor, task.getId());

        assertThat(response).isNotNull();
        assertThat(task.getStatus()).isEqualTo(MaintenanceTaskStatus.in_progress);
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getAssignedTo()).isEqualTo(staffUser);
    }

    @Test
    void completeTask_success() {
        unit.setStatus(StorageUnitStatus.maintenance);

        MaintenanceTask task = new MaintenanceTask();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        task.setFacility(facility);
        task.setStorageUnit(unit);
        task.setTitle("Bảo trì sàn gian");
        task.setStatus(MaintenanceTaskStatus.in_progress);

        CompleteMaintenanceTaskRequest req = new CompleteMaintenanceTaskRequest(
            "Đã sơn lại sàn và vệ sinh sạch sẽ",
            List.of("https://res.cloudinary.com/demo/image/upload/v1/after.jpg"),
            true
        );

        when(maintenanceTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MaintenanceTaskResponse response = maintenanceService.completeTask(staffActor, task.getId(), req);

        assertThat(response).isNotNull();
        assertThat(task.getStatus()).isEqualTo(MaintenanceTaskStatus.completed);
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getResultReport()).isEqualTo("Đã sơn lại sàn và vệ sinh sạch sẽ");
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.available);
        verify(storageUnitRepository).save(unit);
    }

    @Test
    void cancelTask_success() {
        MaintenanceTask task = new MaintenanceTask();
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        task.setFacility(facility);
        task.setStorageUnit(unit);
        task.setTitle("Vệ sinh định kỳ");
        task.setStatus(MaintenanceTaskStatus.open);

        when(maintenanceTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MaintenanceTaskResponse response = maintenanceService.cancelTask(managerActor, task.getId(), "Không cần thiết");

        assertThat(response).isNotNull();
        assertThat(task.getStatus()).isEqualTo(MaintenanceTaskStatus.cancelled);
        assertThat(task.getResultReport()).contains("Không cần thiết");
    }

    @Test
    void getUnitMaintenanceHistory_success() {
        MaintenanceTask task1 = new MaintenanceTask();
        ReflectionTestUtils.setField(task1, "id", UUID.randomUUID());
        task1.setFacility(facility);
        task1.setStorageUnit(unit);
        task1.setTitle("Task 1");

        when(storageUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        when(maintenanceTaskRepository.findByStorageUnit_IdOrderByCreatedAtDesc(unit.getId()))
            .thenReturn(List.of(task1));

        List<MaintenanceTaskResponse> history = maintenanceService.getUnitMaintenanceHistory(managerActor, unit.getId());

        assertThat(history).hasSize(1);
        assertThat(history.get(0).title()).isEqualTo("Task 1");
    }
}
