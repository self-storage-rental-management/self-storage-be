package com.storagehub.domain.repo;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    Optional<Reservation> findByReservationCode(String reservationCode);
    Optional<Reservation> findByIdAndCustomer_Id(UUID id, UUID customerId);
    Optional<Reservation> findByCustomer_IdAndIdempotencyKey(UUID customerId, String idempotencyKey);
    List<Reservation> findAllByCustomer_IdAndStatusOrderByCreatedAtDesc(UUID customerId, ReservationStatus status);
    Page<Reservation> findAllByCustomer_Id(UUID customerId, Pageable pageable);
    Page<Reservation> findAllByCustomer_IdAndStatus(
        UUID customerId,
        ReservationStatus status,
        Pageable pageable
    );

    @Query("""
        select count(reservation) from Reservation reservation
        where reservation.facility.id = :facilityId
          and reservation.unitType.id = :unitTypeId
          and reservation.startDate < :endDate
          and reservation.endDate > :startDate
          and (
            reservation.status in :permanentStatuses
            or (
              reservation.status in :temporaryStatuses
              and reservation.holdExpiresAt > :now
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

    List<Reservation> findTop100ByStatusInAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
        java.util.Collection<ReservationStatus> statuses,
        Instant now
    );

    @Query("""
        select reservation from Reservation reservation
        where reservation.status = com.storagehub.domain.model.ReservationStatus.CONFIRMED
          and reservation.assignedUnit is null
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:scoped = false or reservation.facility.id in :facilityIds)
        """)
    Page<Reservation> findReservationsAwaitingUnitAssignment(
        @Param("facilityId") UUID facilityId,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") java.util.Collection<UUID> facilityIds,
        Pageable pageable
    );
}
