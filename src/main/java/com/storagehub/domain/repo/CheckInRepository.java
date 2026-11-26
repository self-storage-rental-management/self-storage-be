package com.storagehub.domain.repo;

import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckInRepository extends JpaRepository<CheckIn, UUID> {
    @Query("""
        select c from CheckIn c join c.inventoryConfirmation confirmation where confirmation.inventoryRefusalSubmittedAt is not null
          and confirmation.inventoryRefusalApproved is null
          and (:scoped = false or c.reservation.facility.id in :facilities)
        order by confirmation.inventoryRefusalSubmittedAt
        """)
    java.util.List<CheckIn> pendingInventoryRefusals(@Param("scoped") boolean scoped,
        @Param("facilities") java.util.Collection<UUID> facilities);
    @Query("""
        select c from CheckIn c join fetch c.reservation r where r.arrivalReviewOpenedAt is not null
        and c.status = scheduled and r.status in (UNIT_RESERVED, READY_FOR_CHECKIN)
        and (:scoped = false or r.facility.id in :facilities)
        order by r.arrivalReviewOpenedAt
        """)
    java.util.List<CheckIn> pendingArrivalReviews(@Param("scoped") boolean scoped,
        @Param("facilities") java.util.List<UUID> facilities);

    boolean existsByReservation_IdAndStatus(UUID reservationId, CheckInStatus status);
    @Query("select c.id from CheckIn c where c.reservation.id = :reservationId")
    Optional<UUID> attendanceCheckInId(@Param("reservationId") UUID reservationId);

    Optional<CheckIn> findByReservation_Id(UUID reservationId);

    @Query("select c.id from CheckIn c where c.status = :status and c.checkedInAt is not null order by c.checkedInAt, c.id")
    List<UUID> findPendingReceiptIds(@Param("status") CheckInStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkIn from CheckIn checkIn where checkIn.id = :id")
    Optional<CheckIn> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CheckIn c where c.reservation.id = :id and c.reservation.customer.id = :customerId")
    Optional<CheckIn> findOwnedByReservationForUpdate(@Param("id") UUID id, @Param("customerId") UUID customerId);

    long countByReservation_Facility_IdAndStatusAndCheckedInAtBetween(
        UUID facilityId,
        CheckInStatus status,
        java.time.Instant start,
        java.time.Instant end
    );

    long countByStatusAndCheckedInAtBetween(
        CheckInStatus status,
        java.time.Instant start,
        java.time.Instant end
    );
}
