package com.storagehub.domain.repo;

import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.TaskPriority;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceTaskRepository extends JpaRepository<MaintenanceTask, UUID> {

    List<MaintenanceTask> findByStorageUnit_IdOrderByCreatedAtDesc(UUID storageUnitId);

    List<MaintenanceTask> findByStorageUnit_IdAndStatusIn(UUID storageUnitId, Collection<MaintenanceTaskStatus> statuses);

    @Query("""
        select t from MaintenanceTask t
        where (:facilityId is null or t.facility.id = :facilityId)
          and (:storageUnitId is null or t.storageUnit.id = :storageUnitId)
          and (:status is null or t.status = :status)
          and (:priority is null or t.priority = :priority)
          and (:assignedStaffId is null or (t.assignedTo is not null and t.assignedTo.id = :assignedStaffId))
          and (:allowedFacilityIds is null or t.facility.id in :allowedFacilityIds)
          and (
            :q is null or :q = ''
            or lower(t.title) like lower(concat('%', :q, '%'))
            or lower(t.reason) like lower(concat('%', :q, '%'))
            or lower(t.storageUnit.code) like lower(concat('%', :q, '%'))
            or lower(t.facility.name) like lower(concat('%', :q, '%'))
          )
        """)
    Page<MaintenanceTask> searchTasks(
        @Param("facilityId") UUID facilityId,
        @Param("storageUnitId") UUID storageUnitId,
        @Param("status") MaintenanceTaskStatus status,
        @Param("priority") TaskPriority priority,
        @Param("assignedStaffId") UUID assignedStaffId,
        @Param("allowedFacilityIds") Collection<UUID> allowedFacilityIds,
        @Param("q") String q,
        Pageable pageable
    );

    long countByFacility_IdAndStatusIn(UUID facilityId, Collection<MaintenanceTaskStatus> statuses);

    long countByStatusIn(Collection<MaintenanceTaskStatus> statuses);
}
