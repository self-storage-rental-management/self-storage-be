package com.storagehub.domain.repo;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    Optional<Reservation> findByReservationCode(String reservationCode);
    Optional<Reservation> findByIdAndCustomer_Id(UUID id, UUID customerId);
    List<Reservation> findAllByCustomer_IdAndStatusOrderByCreatedAtDesc(UUID customerId, ReservationStatus status);
}
