package com.storagehub.domain.repo;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    long countByCustomer_IdAndStatusIn(UUID customerId, java.util.Collection<ReservationStatus> statuses);
    long countByFacility_IdAndStatusIn(UUID facilityId, java.util.Collection<ReservationStatus> statuses);
    boolean existsByReservationCode(String reservationCode);
    boolean existsByFacility_Id(UUID facilityId);
    boolean existsByUnitType_Id(UUID unitTypeId);
    Optional<Reservation> findByReservationCode(String reservationCode);
    Optional<Reservation> findByIdAndCustomer_Id(UUID id, UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from Reservation reservation where reservation.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select reservation from Reservation reservation
        where reservation.id = :id
          and reservation.customer.id = :customerId
        """)
    Optional<Reservation> findOwnedByIdForUpdate(
        @Param("id") UUID id,
        @Param("customerId") UUID customerId
    );

    Optional<Reservation> findByCustomer_IdAndIdempotencyKey(UUID customerId, String idempotencyKey);
    List<Reservation> findAllByCustomer_IdAndStatusOrderByCreatedAtDesc(UUID customerId, ReservationStatus status);
    Page<Reservation> findAllByCustomer_Id(UUID customerId, Pageable pageable);
    Page<Reservation> findAllByCustomer_IdAndArchivedAtIsNull(UUID customerId, Pageable pageable);
    Page<Reservation> findAllByCustomer_IdAndArchivedAtIsNullAndStatusNot(
        UUID customerId, ReservationStatus excludedStatus, Pageable pageable
    );
    @Query("""
        select reservation from Reservation reservation
        where reservation.customer.id = :customerId
          and reservation.customerArchivedAt is null
          and reservation.status <> com.storagehub.domain.model.ReservationStatus.REJECTED
          and (
            reservation.archivedAt is null
            or reservation.status = com.storagehub.domain.model.ReservationStatus.EXPIRED
          )
        """)
    Page<Reservation> findCustomerVisibleReservations(
        @Param("customerId") UUID customerId,
        Pageable pageable
    );
    Page<Reservation> findAllByCustomer_IdAndStatus(
        UUID customerId,
        ReservationStatus status,
        Pageable pageable
    );
    Page<Reservation> findAllByCustomer_IdAndStatusAndCustomerArchivedAtIsNull(
        UUID customerId, ReservationStatus status, Pageable pageable
    );
    Page<Reservation> findAllByCustomer_IdAndStatusAndArchivedAtIsNull(
        UUID customerId, ReservationStatus status, Pageable pageable
    );
    @Query("""
        select count(reservation) from Reservation reservation
        where reservation.facility.id = :facilityId
          and reservation.unitType.id = :unitTypeId
          and reservation.assignedUnit is null
          and reservation.startDate < :endDate
          and reservation.endDate > :startDate
          and (
            reservation.status in :permanentStatuses
            or (
              reservation.status in :temporaryStatuses
              and (
                (reservation.status = com.storagehub.domain.model.ReservationStatus.PAYMENT_GRACE
                  and reservation.complaintExpiresAt > :now)
                or
                (reservation.status <> com.storagehub.domain.model.ReservationStatus.PAYMENT_GRACE
                  and reservation.holdExpiresAt > :now)
              )
            )
          )
        """)
    long countCapacityHoldingReservations(
        @Param("facilityId") UUID facilityId,
        @Param("unitTypeId") UUID unitTypeId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("now") Instant now,
        @Param("temporaryStatuses") java.util.Collection<ReservationStatus> temporaryStatuses,
        @Param("permanentStatuses") java.util.Collection<ReservationStatus> permanentStatuses
    );

    @Query("""
        select case when count(reservation) > 0 then true else false end
        from Reservation reservation
        where reservation.unitType.id = :unitTypeId
          and (
            reservation.status in :permanentStatuses
            or (
              reservation.status in :temporaryStatuses
              and reservation.holdExpiresAt > :now
            )
          )
        """)
    boolean existsLiveCapacityHoldByUnitTypeId(
        @Param("unitTypeId") UUID unitTypeId,
        @Param("now") Instant now,
        @Param("temporaryStatuses") java.util.Collection<ReservationStatus> temporaryStatuses,
        @Param("permanentStatuses") java.util.Collection<ReservationStatus> permanentStatuses
    );

    @Query("""
        select r.palletHeldUnit.id from Reservation r
        where r.unitType.id = :unitTypeId
          and r.palletHeldUnit is not null and r.assignedUnit is null
          and (r.status in :permanentStatuses
            or (r.status in :temporaryStatuses and r.holdExpiresAt > :now))
        """)
    java.util.List<UUID> findLivePalletHoldUnitIds(
        @Param("unitTypeId") UUID unitTypeId,
        @Param("now") Instant now,
        @Param("temporaryStatuses") java.util.Collection<ReservationStatus> temporaryStatuses,
        @Param("permanentStatuses") java.util.Collection<ReservationStatus> permanentStatuses
    );

    @Query("""
        select r.palletHeldUnit.id from Reservation r
        where r.unitType.id = :unitTypeId
          and r.palletHeldUnit is not null and r.assignedUnit is null
          and r.startDate < :endDate
          and r.endDate > :startDate
          and (r.status in :permanentStatuses
            or (r.status in :temporaryStatuses and r.holdExpiresAt > :now))
        """)
    java.util.List<UUID> findLivePalletHoldUnitIdsForPeriod(
        @Param("unitTypeId") UUID unitTypeId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("now") Instant now,
        @Param("temporaryStatuses") java.util.Collection<ReservationStatus> temporaryStatuses,
        @Param("permanentStatuses") java.util.Collection<ReservationStatus> permanentStatuses
    );

    @Query("""
        select reservation from Reservation reservation
        where reservation.status = com.storagehub.domain.model.ReservationStatus.AWAITING_REVIEW
          and reservation.goodsReviewStatus = com.storagehub.domain.model.GoodsReviewStatus.PENDING
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:scoped = false or reservation.facility.id in :facilityIds)
        """)
    Page<Reservation> findPendingGoodsReviews(
        @Param("facilityId") UUID facilityId,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        Pageable pageable
    );

    @Query("""
        select reservation from Reservation reservation
        left join reservation.assignedUnit assignedUnit
        where reservation.status in :activeStatuses
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:status is null or reservation.status = :status)
          and (:scoped = false or reservation.facility.id in :facilityIds)
          and (:q is null
               or lower(reservation.reservationCode) like lower(concat('%', :q, '%'))
               or lower(reservation.customer.email) like lower(concat('%', :q, '%'))
               or lower(reservation.customer.fullName) like lower(concat('%', :q, '%'))
               or lower(coalesce(assignedUnit.code, '')) like lower(concat('%', :q, '%')))
        order by case when reservation.appointmentAt is null then 1 else 0 end,
                 reservation.appointmentAt asc,
                 reservation.createdAt asc
        """)
    Page<Reservation> findStaffActiveReservations(
        @Param("activeStatuses") java.util.Collection<ReservationStatus> activeStatuses,
        @Param("facilityId") UUID facilityId,
        @Param("status") ReservationStatus status,
        @Param("q") String q,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        Pageable pageable
    );

    @Query("""
        select reservation from Reservation reservation
        where reservation.status in (
            com.storagehub.domain.model.ReservationStatus.CONFIRMED,
            com.storagehub.domain.model.ReservationStatus.UNIT_RESERVED,
            com.storagehub.domain.model.ReservationStatus.READY_FOR_CHECKIN
          )
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:unitTypeId is null or reservation.unitType.id = :unitTypeId)
          and (:scoped = false or reservation.facility.id in :facilityIds)
          and (:q is null
               or lower(reservation.reservationCode) like lower(concat('%', :q, '%'))
               or lower(reservation.customer.email) like lower(concat('%', :q, '%'))
               or lower(reservation.customer.fullName) like lower(concat('%', :q, '%')))
        """)
    Page<Reservation> findUnitAssignmentCandidates(
        @Param("facilityId") UUID facilityId,
        @Param("unitTypeId") UUID unitTypeId,
        @Param("q") String q,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        Pageable pageable
    );

    interface CheckInWorkRow {
        Reservation getReservation();
        com.storagehub.domain.model.CheckIn getCheckIn();
        com.storagehub.domain.model.UnitAssignment getAssignment();
    }

    String CHECK_IN_WORK_FROM = """
        from Reservation r
        left join CheckIn c on c.reservation = r
        join UnitAssignment a on a.reservation = r
        where a.id = (select latest.id from UnitAssignment latest where latest.reservation = r
                      order by latest.assignedAt desc, latest.id desc limit 1)
          and (
            (r.status in (UNIT_RESERVED, READY_FOR_CHECKIN, AWAITING_CUSTOMER_RECEIPT, PREPARING, COMPLETED)
              and r.assignedUnit is not null and a.status in (ACTIVE, COMPLETED))
            or (:workGroup = 'PROCESSED' and c.status in (completed, rejected, no_show, cancelled))
          )
          and (:workGroup = 'ALL' or :workGroup = case
            when c.status in (completed, rejected, no_show, cancelled) or r.status = COMPLETED then 'PROCESSED'
            else 'ONGOING' end)
          and (:facilityId is null or r.facility.id = :facilityId)
          and (:scoped = false or r.facility.id in :facilityIds)
          and (:q is null
               or lower(r.reservationCode) like lower(concat('%', :q, '%'))
               or lower(r.customer.email) like lower(concat('%', :q, '%'))
               or lower(r.customer.fullName) like lower(concat('%', :q, '%'))
               or lower(a.storageUnit.code) like lower(concat('%', :q, '%')))
          and (:statusFilter = false or (:unscheduled = true and c.id is null) or c.status = :checkInStatus)
          and (:scheduledFrom is null or coalesce(r.appointmentAt, c.scheduledAt) >= :scheduledFrom)
          and (:scheduledTo is null or coalesce(r.appointmentAt, c.scheduledAt) < :scheduledTo)
        """;

    @Query(value = "select r as reservation, c as checkIn, a as assignment " + CHECK_IN_WORK_FROM + """
        order by case
          when c.status in (completed, rejected, no_show, cancelled) or r.status = COMPLETED then 5
          when coalesce(r.appointmentAt, c.scheduledAt) < :firstValidAppointment
            and (c.id is null or c.status in (scheduled, identity_verified, contract_signed, payment_collected)) then 4
          when c.status in (identity_verified, contract_signed, payment_collected, handover_pending_receipt,
                            setup_in_progress, setup_completed) then 0
          when coalesce(r.appointmentAt, c.scheduledAt) <= :now then 1
          when coalesce(r.appointmentAt, c.scheduledAt) is not null then 2
          else 3 end,
          coalesce(r.appointmentAt, c.scheduledAt) asc nulls last, r.reservationCode asc, r.id asc
        """, countQuery = "select count(r.id) " + CHECK_IN_WORK_FROM)
    Page<CheckInWorkRow> findCheckInWork(
        @Param("workGroup") String workGroup,
        @Param("now") Instant now,
        @Param("firstValidAppointment") Instant firstValidAppointment,
        @Param("facilityId") UUID facilityId,
        @Param("q") String q,
        @Param("statusFilter") boolean statusFilter,
        @Param("unscheduled") boolean unscheduled,
        @Param("checkInStatus") com.storagehub.domain.model.CheckInStatus checkInStatus,
        @Param("scheduledFrom") Instant scheduledFrom,
        @Param("scheduledTo") Instant scheduledTo,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        Pageable pageable
    );

    List<Reservation> findByFacility_IdInAndStatusInAndAssignedUnitIsNotNull(
        java.util.Collection<UUID> facilityIds,
        java.util.Collection<ReservationStatus> statuses
    );

    List<Reservation> findByStatusInAndAssignedUnitIsNotNull(
        java.util.Collection<ReservationStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Reservation> findTop100ByStatusInAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
        java.util.Collection<ReservationStatus> statuses,
        Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Reservation> findTop100ByStatusAndPaymentExpiresAtLessThanEqualOrderByPaymentExpiresAtAsc(
        ReservationStatus status,
        Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Reservation> findTop100ByStatusAndComplaintExpiresAtLessThanEqualOrderByComplaintExpiresAtAsc(
        ReservationStatus status,
        Instant now
    );

}
