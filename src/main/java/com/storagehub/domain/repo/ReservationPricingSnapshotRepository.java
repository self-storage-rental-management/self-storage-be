package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReservationPricingSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationPricingSnapshotRepository
    extends JpaRepository<ReservationPricingSnapshot, UUID> {

    Optional<ReservationPricingSnapshot> findByReservation_Id(UUID reservationId);
}
