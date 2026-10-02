package com.storagehub.domain.repo;

import com.storagehub.domain.model.PaymentComplaint;
import com.storagehub.domain.model.PaymentComplaintStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentComplaintRepository extends JpaRepository<PaymentComplaint, UUID> {
    long countByCustomer_IdAndSubmittedAtAfter(UUID customerId, java.time.Instant submittedAfter);
    Optional<PaymentComplaint> findByReservation_Id(UUID reservationId);
    Optional<PaymentComplaint> findByReservation_IdAndCustomer_Id(UUID reservationId, UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select complaint from PaymentComplaint complaint where complaint.id = :id")
    Optional<PaymentComplaint> findByIdForUpdate(@Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<PaymentComplaint> findTop100ByStatusAndReviewDueAtLessThanEqualOrderByReviewDueAtAsc(
        PaymentComplaintStatus status, Instant now
    );

    @Query("""
        select complaint from PaymentComplaint complaint
        where complaint.status in :statuses
          and (:facilityId is null or complaint.reservation.facility.id = :facilityId)
          and (:scoped = false or complaint.reservation.facility.id in :facilityIds)
        order by
          case when complaint.status = com.storagehub.domain.model.PaymentComplaintStatus.REVIEW_OVERDUE then 0 else 1 end,
          complaint.reviewDueAt asc,
          complaint.createdAt desc
        """)
    Page<PaymentComplaint> findManagerQueue(
        @Param("statuses") Collection<PaymentComplaintStatus> statuses,
        @Param("facilityId") UUID facilityId,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") Collection<UUID> facilityIds,
        Pageable pageable
    );

    @Query(value = """
        select reservation, complaint
        from Reservation reservation
        left join PaymentComplaint complaint on complaint.reservation = reservation
        where reservation.archivedAt is null
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:scoped = false or reservation.facility.id in :facilityIds)
        order by
          case
            when complaint.status = com.storagehub.domain.model.PaymentComplaintStatus.REVIEW_OVERDUE then 0
            when complaint.status = com.storagehub.domain.model.PaymentComplaintStatus.PENDING then 1
            when reservation.status = com.storagehub.domain.model.ReservationStatus.PAYMENT_GRACE then 2
            else 3
          end asc,
          case when complaint.status in (
            com.storagehub.domain.model.PaymentComplaintStatus.REVIEW_OVERDUE,
            com.storagehub.domain.model.PaymentComplaintStatus.PENDING
          ) then complaint.reviewDueAt else null end asc,
          reservation.createdAt desc
        """, countQuery = """
        select count(reservation)
        from Reservation reservation
        where reservation.archivedAt is null
          and (:facilityId is null or reservation.facility.id = :facilityId)
          and (:scoped = false or reservation.facility.id in :facilityIds)
        """)
    Page<Object[]> findManagerPaymentQueue(
        @Param("facilityId") UUID facilityId,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") Collection<UUID> facilityIds,
        Pageable pageable
    );
}
