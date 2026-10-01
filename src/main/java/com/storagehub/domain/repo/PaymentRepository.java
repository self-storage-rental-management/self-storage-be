package com.storagehub.domain.repo;

import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
        UUID reservationId,
        PaymentType purpose
    );
    List<Payment> findAllByReservation_IdAndStatusIn(
        UUID reservationId,
        Collection<com.storagehub.domain.model.PaymentStatus> statuses
    );
}
