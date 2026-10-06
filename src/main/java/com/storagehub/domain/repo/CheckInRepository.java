package com.storagehub.domain.repo;

import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
