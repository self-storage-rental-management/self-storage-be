package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReservationEmailVerification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationEmailVerificationRepository
    extends JpaRepository<ReservationEmailVerification, UUID> {

    Optional<ReservationEmailVerification> findByReservation_Id(UUID reservationId);
}
