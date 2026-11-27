package com.storagehub.domain.repo;

import com.storagehub.domain.model.ViolationRecovery;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ViolationRecoveryRepository extends JpaRepository<ViolationRecovery, UUID> {
    List<ViolationRecovery> findByTask_AssignedTo_Id(UUID staffId, Pageable page);

    // Order the complete authorized queue in SQL, before applying the page limit.
    // A completed recovery may still have pickup/finance work; cancellation never authorizes movement.
    String QUEUE_ORDER = " order by case when v.stage = 'CANCELLED' then 3 "
        + "when v.stage = 'COMPLETED' and v.financePending = false and v.pickupReviewAt is null then 2 else 0 end, ";
    String QUEUE_DATES = "case when v.stage = 'COMPLETED' then v.pickupReviewAt else coalesce(v.actionDueAt, v.deadlineAt) end asc nulls last, "
        + "case when v.stage in ('COMPLETED', 'CANCELLED') then coalesce(v.completedAt, v.createdAt) end desc, "
        + "v.requiredAt asc nulls last, v.createdAt asc, v.id asc";
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ViolationRecovery v where v.id = :id")
    Optional<ViolationRecovery> lock(@Param("id") UUID id);

    List<ViolationRecovery> findByRental_IdOrderByCreatedAtDesc(UUID rentalId);
    List<ViolationRecovery> findBySourceReturnIdOrderByCreatedAtDesc(UUID sourceReturnId);
    @Query("select v from ViolationRecovery v where v.rental.customer.id = :customerId" + QUEUE_ORDER
        + "case when v.stage = 'AWAITING_ATTENDANCE' then 0 else 1 end, " + QUEUE_DATES)
    List<ViolationRecovery> findByRental_Customer_IdOrderByCreatedAtDesc(@Param("customerId") UUID customerId, Pageable page);
    @Query(value = "select v from ViolationRecovery v where v.rental.customer.id = :customerId and v.requiredAt is not null"
        + QUEUE_ORDER + "case when v.stage = 'AWAITING_ATTENDANCE' then 0 else 1 end, " + QUEUE_DATES,
        countQuery = "select count(v) from ViolationRecovery v where v.rental.customer.id = :customerId and v.requiredAt is not null")
    org.springframework.data.domain.Page<ViolationRecovery> customerPage(@Param("customerId") UUID customerId, Pageable page);
    @Query("select v from ViolationRecovery v where v.task.assignedTo.id = :staffId" + QUEUE_ORDER
        + "case when v.stage in ('AWAITING_ACCEPTANCE', 'AWAITING_ATTENDANCE', 'APPROVED', 'IN_PROGRESS') then 0 else 1 end, " + QUEUE_DATES)
    List<ViolationRecovery> findByTask_AssignedTo_IdOrderByCreatedAtDesc(@Param("staffId") UUID staffId, Pageable page);
    String MANAGER_ORDER = QUEUE_ORDER
        + "case when v.stage in ('REVIEW_REQUIRED', 'AWAITING_ASSIGNMENT', 'AWAITING_APPROVAL', 'BLOCKED') then 0 else 1 end, "
        + "case when v.stage = 'BLOCKED' then 0 else 1 end, " + QUEUE_DATES;
    @Query("select v from ViolationRecovery v where (:global = true or v.rental.facility.id in :facilities) "
        + "and (:active = false or v.stage not in ('COMPLETED', 'CANCELLED'))" + QUEUE_ORDER
        + "case when :business = true and (v.escalated = true or v.financePending = true or v.pickupEscalated = true) then 0 "
        + "when :business = false and v.escalated = false and v.stage in ('REVIEW_REQUIRED', 'AWAITING_ASSIGNMENT', 'AWAITING_APPROVAL', 'BLOCKED') then 0 else 1 end, "
        + "case when v.stage = 'BLOCKED' then 0 else 1 end, " + QUEUE_DATES)
    List<ViolationRecovery> operationsQueue(@Param("global") boolean global,
        @Param("facilities") java.util.Collection<UUID> facilities, @Param("active") boolean active,
        @Param("business") boolean business, Pageable page);
    @Query("select v from ViolationRecovery v where v.rental.facility.id in :facilities" + MANAGER_ORDER)
    List<ViolationRecovery> findByRental_Facility_IdInOrderByCreatedAtDesc(@Param("facilities") java.util.Collection<UUID> facilities, Pageable page);
    @Query("select v from ViolationRecovery v" + MANAGER_ORDER)
    List<ViolationRecovery> findAllByOrderByCreatedAtDesc(Pageable page);
    @Query("select v from ViolationRecovery v where v.stage not in :stages" + MANAGER_ORDER)
    List<ViolationRecovery> findByStageNotInOrderByCreatedAtDesc(java.util.Collection<ViolationRecovery.Stage> stages, Pageable page);
    @Query("select v from ViolationRecovery v where v.rental.facility.id in :facilities and v.stage not in :stages" + MANAGER_ORDER)
    List<ViolationRecovery> findByRental_Facility_IdInAndStageNotInOrderByCreatedAtDesc(
        java.util.Collection<UUID> facilities, java.util.Collection<ViolationRecovery.Stage> stages, Pageable page);
    List<ViolationRecovery> findByStageOrderByCreatedAtAsc(ViolationRecovery.Stage stage, Pageable page);
    List<ViolationRecovery> findByEscalatedTrueAndStageInOrderByCreatedAtDesc(
        java.util.Collection<ViolationRecovery.Stage> stages, Pageable page);
    List<ViolationRecovery> findByRental_Facility_IdInAndEscalatedTrueAndStageInOrderByCreatedAtDesc(
        java.util.Collection<UUID> facilities, java.util.Collection<ViolationRecovery.Stage> stages, Pageable page);

    @Query("""
        select v.id from ViolationRecovery v
        where v.actionDueAt <= :now or v.responseCheckAt <= :now
           or (v.deadlineAt <= :now and v.overdueAt is null and v.completedAt is null and v.stage <> 'CANCELLED')
           or (v.pickupReviewAt <= :now and v.pickupEscalated = false)
        order by coalesce(v.actionDueAt, v.responseCheckAt, v.deadlineAt, v.pickupReviewAt) asc, v.createdAt asc, v.id asc
        """)
    List<UUID> due(@Param("now") Instant now, Pageable page);
}
