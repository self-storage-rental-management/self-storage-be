package com.storagehub.domain.repo;

import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckInRepository extends JpaRepository<CheckIn, UUID> {
    boolean existsByReservation_IdAndStatus(UUID reservationId, CheckInStatus status);

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
