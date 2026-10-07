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
    Optional<Reservation> findByReservationCode(String reservationCode);
    Optional<Reservation> findByIdAndCustomer_Id(UUID id, UUID customerId);

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
    Page<Reservation> findAllByCustomer_IdAndStatus(
        UUID customerId,
        ReservationStatus status,
        Pageable pageable
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
        where reservation.status = com.storagehub.domain.model.ReservationStatus.CONFIRMED
          and reservation.assignedUnit is null
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:unitTypeId is null or reservation.unitType.id = :unitTypeId)
          and (:scoped = false or reservation.facility.id in :facilityIds)
          and (
            :query is null
            or lower(reservation.reservationCode) like lower(concat('%', :query, '%'))
            or lower(reservation.customer.fullName) like lower(concat('%', :query, '%'))
            or lower(reservation.customer.email) like lower(concat('%', :query, '%'))
          )
        """)
    Page<Reservation> findUnitAssignmentCandidates(
        @Param("facilityId") UUID facilityId,
        @Param("unitTypeId") UUID unitTypeId,
        @Param("query") String query,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        Pageable pageable
    );

    @Query("""
        select reservation from Reservation reservation
        where reservation.status in (
            com.storagehub.domain.model.ReservationStatus.UNIT_RESERVED,
            com.storagehub.domain.model.ReservationStatus.READY_FOR_CHECKIN,
            com.storagehub.domain.model.ReservationStatus.AWAITING_CUSTOMER_RECEIPT,
            com.storagehub.domain.model.ReservationStatus.COMPLETED
          )
          and reservation.assignedUnit is not null
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:scoped = false or reservation.facility.id in :facilityIds)
          and (
            :query is null
            or lower(reservation.reservationCode) like lower(concat('%', :query, '%'))
            or lower(reservation.customer.fullName) like lower(concat('%', :query, '%'))
            or lower(reservation.customer.email) like lower(concat('%', :query, '%'))
            or lower(reservation.assignedUnit.code) like lower(concat('%', :query, '%'))
          )
        """)
    Page<Reservation> findCheckInWork(
        @Param("facilityId") UUID facilityId,
        @Param("query") String query,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        Pageable pageable
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
