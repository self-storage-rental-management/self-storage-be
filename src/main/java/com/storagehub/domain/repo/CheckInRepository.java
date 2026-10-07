package com.storagehub.domain.repo;

import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckInRepository extends JpaRepository<CheckIn, UUID> {
    boolean existsByReservation_IdAndStatus(UUID reservationId, CheckInStatus status);
    Optional<CheckIn> findByReservation_Id(UUID reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkIn from CheckIn checkIn where checkIn.id = :id")
    Optional<CheckIn> findByIdForUpdate(@Param("id") UUID id);

    Optional<CheckIn> findByReservation_Id(UUID reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CheckIn c where c.id = :id")
    Optional<CheckIn> findByIdForUpdate(@Param("id") UUID id);

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
