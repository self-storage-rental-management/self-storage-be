package com.storagehub.domain.repo;

import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment where payment.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    Optional<Payment> findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
        UUID reservationId,
        PaymentType purpose
    );
    List<Payment> findAllByReservation_IdAndStatusIn(
        UUID reservationId,
        Collection<com.storagehub.domain.model.PaymentStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select payment from Payment payment
        where payment.reservation.id = :reservationId
          and payment.purpose = :purpose
        order by payment.createdAt desc
        """)
    List<Payment> findReservationPaymentsForUpdate(
        @Param("reservationId") UUID reservationId,
        @Param("purpose") PaymentType purpose
    );
}
