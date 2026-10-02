package com.storagehub.domain.repo;

import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInRepository extends JpaRepository<CheckIn, UUID> {
    boolean existsByReservation_IdAndStatus(UUID reservationId, CheckInStatus status);
}
