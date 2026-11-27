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
    String CUSTOMER_QUEUE_ORDER = " order by case "
        + "when reservation.status in (AWAITING_EMAIL, AWAITING_PAYMENT, PAYMENT_GRACE, PAYMENT_REVIEW, AWAITING_CUSTOMER_RECEIPT, UNIT_RESERVED, READY_FOR_CHECKIN) then 0 "
        + "when reservation.status in (COMPLETED, EXPIRED, CANCELLED, REJECTED) then 2 else 1 end, "
        + "case when reservation.status in (AWAITING_PAYMENT, PAYMENT_GRACE) then coalesce(reservation.paymentExpiresAt, reservation.holdExpiresAt) "
        + "when reservation.status = AWAITING_EMAIL then reservation.holdExpiresAt "
        + "when reservation.status in (UNIT_RESERVED, READY_FOR_CHECKIN) then reservation.appointmentAt end asc nulls last, "
        + "case when reservation.status in (COMPLETED, EXPIRED, CANCELLED, REJECTED) then coalesce(reservation.cancelledAt, reservation.expiredAt, reservation.createdAt) end desc, "
        + "reservation.createdAt asc, reservation.id asc";
    long countByCustomer_IdAndStatusIn(UUID customerId, java.util.Collection<ReservationStatus> statuses);
    long countByFacility_IdAndStatusIn(UUID facilityId, java.util.Collection<ReservationStatus> statuses);
    @Query("""
        select r.id from Reservation r left join CheckIn c on c.reservation = r
        where r.handoverTermsVersion in :versions and r.status in (UNIT_RESERVED, READY_FOR_CHECKIN)
          and r.assignedUnit is not null
          and r.handoverArrivedAt is null and r.arrivalReviewOpenedAt is null
          and (c.id is null or c.status = scheduled)
          and (r.appointmentAt < :expired or (r.appointmentAt >= :currentWindow and r.appointmentAt < :oneDay
                   and r.attendanceReminderHours <> 2)
               or (r.handoverTermsVersion = :currentVersion and r.appointmentAt <= :firstReminder and r.attendanceReminderHours = 0))
        order by r.appointmentAt, r.id
        """)
    List<UUID> findAttendanceDue(@Param("versions") List<String> versions,
        @Param("currentVersion") String currentVersion, @Param("expired") Instant expired,
        @Param("currentWindow") Instant currentWindow, @Param("oneDay") Instant oneDay,
        @Param("firstReminder") Instant firstReminder);

    @Query("""
        select r.id from Reservation r join CheckIn c on c.reservation = r
        where r.status in (UNIT_RESERVED, READY_FOR_CHECKIN) and coalesce(r.handoverArrivedAt, r.firstHandoverComparisonAt) < :startedBefore
          and c.status in (scheduled, identity_verified, contract_signed, payment_collected)
        """)
    List<UUID> findUnfinishedHandovers(@Param("startedBefore") Instant startedBefore);

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
          and (
            reservation.archivedAt is null
            or reservation.status = com.storagehub.domain.model.ReservationStatus.EXPIRED
            or reservation.status = com.storagehub.domain.model.ReservationStatus.REJECTED
          )
        """ + CUSTOMER_QUEUE_ORDER)
    Page<Reservation> findCustomerVisibleReservations(
        @Param("customerId") UUID customerId,
        Pageable pageable
    );
    Page<Reservation> findAllByCustomer_IdAndStatus(
        UUID customerId,
        ReservationStatus status,
        Pageable pageable
    );
    @Query("select reservation from Reservation reservation where reservation.customer.id = :customerId and reservation.status = :status and reservation.customerArchivedAt is null" + CUSTOMER_QUEUE_ORDER)
    Page<Reservation> findAllByCustomer_IdAndStatusAndCustomerArchivedAtIsNull(
        UUID customerId, ReservationStatus status, Pageable pageable
    );
    @Query("select reservation from Reservation reservation where reservation.customer.id = :customerId and reservation.status = :status and reservation.archivedAt is null" + CUSTOMER_QUEUE_ORDER)
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
        order by case when :order = 'createdAt,desc' then reservation.createdAt end desc,
                 case when :order = 'createdAt,asc' then reservation.createdAt end asc,
                 case when :order = 'appointmentAt,asc' then reservation.appointmentAt end asc nulls last,
                 case when :order <> 'priority' then reservation.id end asc,
                 reservation.goodsReviewDueAt asc nulls last,
                 reservation.createdAt asc, reservation.id asc
        """)
    Page<Reservation> findPendingGoodsReviewsOrdered(
        @Param("facilityId") UUID facilityId,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        @Param("order") String order,
        Pageable pageable
    );
    default Page<Reservation> findPendingGoodsReviews(
        UUID facilityId,
        boolean scoped,
        java.util.Collection<UUID> facilityIds,
        Pageable pageable
    ) { return findPendingGoodsReviewsOrdered(facilityId, scoped, facilityIds, "priority", pageable); }


    @Query("""
        select r from Reservation r
        where r.goodsReviewStatus <> com.storagehub.domain.model.GoodsReviewStatus.NOT_REQUIRED
          and (r.goodsReviewStatus <> com.storagehub.domain.model.GoodsReviewStatus.PENDING
            or r.status <> com.storagehub.domain.model.ReservationStatus.AWAITING_REVIEW)
          and (:facilityId is null or r.facility.id = :facilityId)
          and (:scoped = false or r.facility.id in :facilityIds)
        order by case when :order = 'createdAt,desc' then r.createdAt end desc,
                 case when :order = 'createdAt,asc' then r.createdAt end asc,
                 case when :order = 'appointmentAt,asc' then r.appointmentAt end asc nulls last,
                 case when :order <> 'priority' then r.id end asc,
                 coalesce(r.goodsReviewedAt, r.cancelledAt, r.expiredAt, r.createdAt) desc, r.id asc
        """)
    Page<Reservation> findCompletedGoodsReviewsOrdered(
        @Param("facilityId") UUID facilityId,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        @Param("order") String order,
        Pageable pageable
    );
    default Page<Reservation> findCompletedGoodsReviews(
        UUID facilityId,
        boolean scoped,
        java.util.Collection<UUID> facilityIds,
        Pageable pageable
    ) { return findCompletedGoodsReviewsOrdered(facilityId, scoped, facilityIds, "priority", pageable); }


    @Query("""
        select reservation from Reservation reservation
        left join reservation.assignedUnit assignedUnit
        where reservation.status in :activeStatuses
          and reservation.archivedAt is null
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:status is null or reservation.status = :status)
          and (:scoped = false or reservation.facility.id in :facilityIds)
          and (:q is null
               or lower(reservation.reservationCode) like lower(concat('%', :q, '%'))
               or lower(reservation.customerEmail) like lower(concat('%', :q, '%'))
               or lower(reservation.customerName) like lower(concat('%', :q, '%'))
               or lower(coalesce(assignedUnit.code, '')) like lower(concat('%', :q, '%')))
        order by case when :order = 'createdAt,desc' then reservation.createdAt end desc,
                 case when :order = 'createdAt,asc' then reservation.createdAt end asc,
                 case when :order = 'appointmentAt,asc' then reservation.appointmentAt end asc nulls last,
                 case when :order <> 'priority' then reservation.id end asc,
                 case
                   when reservation.status in (AWAITING_REVIEW, PAYMENT_REVIEW) then 0
                   when reservation.status = COMPLETED then 2
                   else 1 end,
                 case when reservation.status = AWAITING_REVIEW then reservation.goodsReviewDueAt
                      when reservation.status = PAYMENT_REVIEW then reservation.paymentExpiresAt
                      else reservation.appointmentAt end asc nulls last,
                 reservation.createdAt asc, reservation.id asc
        """)
    Page<Reservation> findStaffActiveReservationsOrdered(
        @Param("activeStatuses") java.util.Collection<ReservationStatus> activeStatuses,
        @Param("facilityId") UUID facilityId,
        @Param("status") ReservationStatus status,
        @Param("q") String q,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        @Param("order") String order,
        Pageable pageable
    );
    default Page<Reservation> findStaffActiveReservations(
        java.util.Collection<ReservationStatus> activeStatuses,
        UUID facilityId,
        ReservationStatus status,
        String q,
        boolean scoped,
        java.util.Collection<UUID> facilityIds,
        Pageable pageable
    ) { return findStaffActiveReservationsOrdered(activeStatuses, facilityId, status, q, scoped, facilityIds, "priority", pageable); }


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
               or lower(reservation.customerEmail) like lower(concat('%', :q, '%'))
               or lower(reservation.customerName) like lower(concat('%', :q, '%')))
        order by reservation.appointmentAt asc nulls last, reservation.confirmedAt asc nulls last,
          reservation.createdAt asc, reservation.id asc
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
          and r.archivedAt is null
          and (
            (r.status in (UNIT_RESERVED, READY_FOR_CHECKIN, AWAITING_CUSTOMER_RECEIPT, PREPARING, COMPLETED)
              and r.assignedUnit is not null and a.status in (ACTIVE, COMPLETED))
            or c.inventoryDecisionJson is not null
            or exists (select 1 from Rental recoveryRental where recoveryRental.reservation = r
                       and recoveryRental.inventoryEscalatedAt is not null)
            or r.cancellationType in (HANDOVER_ATTENDANCE_TIMEOUT, HANDOVER_COMPLETION_TIMEOUT)
            or (:workGroup = 'PROCESSED' and c.status in (completed, rejected, no_show, cancelled))
          )
          and (:workGroup = 'ALL' or :workGroup = case
            when exists (select 1 from Rental recoveryRental where recoveryRental.reservation = r
                         and recoveryRental.inventoryEscalatedAt is not null)
              or r.cancellationType = HANDOVER_ATTENDANCE_TIMEOUT or c.status in (completed, rejected, no_show, cancelled) or r.status = COMPLETED
              or (c.inventoryDecisionJson is not null and c.inventoryResolutionStatus in (RETURN_REQUESTED, TRANSFER_REQUESTED, ACCEPTED)) then 'PROCESSED'
            else 'ONGOING' end)
          and (:facilityId is null or r.facility.id = :facilityId)
          and (:scoped = false or r.facility.id in :facilityIds)
          and (:q is null
               or lower(r.reservationCode) like lower(concat('%', :q, '%'))
               or lower(r.customerEmail) like lower(concat('%', :q, '%'))
               or lower(r.customerName) like lower(concat('%', :q, '%'))
               or lower(a.storageUnit.code) like lower(concat('%', :q, '%')))
          and (:statusFilter = false
            or (:recoveryTransferred = true and exists (select 1 from Rental recoveryRental
                where recoveryRental.reservation = r and recoveryRental.inventoryEscalatedAt is not null))
            or (:recoveryTransferred = false and not exists (select 1 from Rental recoveryRental
                where recoveryRental.reservation = r and recoveryRental.inventoryEscalatedAt is not null)
              and ((:unscheduled = true and c.id is null) or c.status = :checkInStatus)))
          and (:phase is null or (
            not exists (select 1 from Rental phaseRental where phaseRental.reservation = r and phaseRental.inventoryEscalatedAt is not null)
            and ((:phase = 'HANDOVER_WAITING' and (c.id is null or c.status = scheduled))
              or (:phase = 'HANDOVER_IN_PROGRESS' and c.status in (identity_verified, contract_signed, payment_collected, handover_pending_receipt))
              or (:phase = 'HANDOVER_STOPPED' and c.status in (rejected, no_show, cancelled)))))
          and (:scheduledFrom is null or coalesce(r.appointmentAt, c.scheduledAt) >= :scheduledFrom)
          and (:scheduledTo is null or coalesce(r.appointmentAt, c.scheduledAt) < :scheduledTo)
        """;

    @Query(value = "select r as reservation, c as checkIn, a as assignment " + CHECK_IN_WORK_FROM + """
        order by
          case when :order = 'createdAt,desc' then r.createdAt end desc,
          case when :order = 'createdAt,asc' then r.createdAt end asc,
          case when :order <> 'priority' then r.id end asc,
          case
          when :workGroup = 'PROCESSED' then 0
            when r.handoverArrivedAt is not null and c.status in (scheduled, identity_verified, contract_signed, payment_collected) then -1
          when c.status = handover_pending_receipt
            and c.checkedInAt <= :receiptConfirmationDueCutoff then 0
          when c.status = handover_pending_receipt
            and c.checkedInAt <= :receiptConfirmationReminderCutoff then 1
          when c.status in (completed, rejected, no_show, cancelled) or r.status = COMPLETED then 7
          when coalesce(r.appointmentAt, c.scheduledAt) < :firstValidAppointment
            and r.handoverArrivedAt is null and c.identityVerifiedAt is null
            and (c.id is null or c.status in (scheduled, identity_verified, contract_signed, payment_collected)) then 6
          when c.status in (handover_pending_receipt, setup_in_progress, setup_completed) then 5
          when coalesce(r.appointmentAt, c.scheduledAt) <= :now then 2
          when coalesce(r.appointmentAt, c.scheduledAt) is not null then 3
          else 6 end,
          case when :workGroup = 'PROCESSED' then coalesce(r.cancelledAt, c.inventoryVerifiedAt,
               c.inventoryDecisionAt, c.rejectionResolvedAt, c.checkedInAt, r.createdAt) end desc,
          case when :workGroup <> 'PROCESSED' then coalesce(r.appointmentAt, c.scheduledAt) end asc nulls last,
          r.createdAt desc, r.id asc
        """, countQuery = "select count(r.id) " + CHECK_IN_WORK_FROM)
    Page<CheckInWorkRow> findCheckInWorkOrdered(
        @Param("workGroup") String workGroup,
        @Param("now") Instant now,
        @Param("firstValidAppointment") Instant firstValidAppointment,
        @Param("receiptConfirmationDueCutoff") Instant receiptConfirmationDueCutoff,
        @Param("receiptConfirmationReminderCutoff") Instant receiptConfirmationReminderCutoff,
        @Param("facilityId") UUID facilityId,
        @Param("q") String q,
        @Param("statusFilter") boolean statusFilter,
        @Param("unscheduled") boolean unscheduled,
        @Param("recoveryTransferred") boolean recoveryTransferred,
        @Param("checkInStatus") com.storagehub.domain.model.CheckInStatus checkInStatus,
        @Param("scheduledFrom") Instant scheduledFrom,
        @Param("scheduledTo") Instant scheduledTo,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        @Param("order") String order,
        @Param("phase") String phase,
        Pageable pageable
    );

    default Page<CheckInWorkRow> findCheckInWork(
        String workGroup,
        Instant now,
        Instant firstValidAppointment,
        Instant receiptConfirmationDueCutoff,
        Instant receiptConfirmationReminderCutoff,
        UUID facilityId,
        String q,
        boolean statusFilter,
        boolean unscheduled,
        boolean recoveryTransferred,
        com.storagehub.domain.model.CheckInStatus checkInStatus,
        Instant scheduledFrom,
        Instant scheduledTo,
        boolean scoped,
        java.util.Collection<UUID> facilityIds,
        Pageable pageable
    ) {
        return findCheckInWorkOrdered(workGroup, now, firstValidAppointment, receiptConfirmationDueCutoff, receiptConfirmationReminderCutoff, facilityId, q, statusFilter, unscheduled, recoveryTransferred, checkInStatus, scheduledFrom, scheduledTo, scoped, facilityIds, "priority", null, pageable);
    }



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
