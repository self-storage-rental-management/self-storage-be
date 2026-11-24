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
    boolean existsByReservation_IdAndStatus(UUID reservationId, CheckInStatus status);
    Optional<CheckIn> findByReservation_Id(UUID reservationId);

    List<CheckIn> findTop100ByStatusAndCheckedInAtIsNotNullOrderByCheckedInAtAsc(CheckInStatus status);

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
