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
    @Query("""
        select p.id from Payment p where p.renewal is not null
        and p.status = com.storagehub.domain.model.PaymentStatus.PAID
        and p.renewalEvidenceRef is not null and p.renewalSigningPreparedAt is null
        and p.failureCode = 'RENEWAL_PAYMENT_RECONCILIATION_REQUIRED'
        and (p.renewalNextRetryAt is null or p.renewalNextRetryAt <= :now)
        order by p.createdAt
        """)
    List<UUID> findRenewalReconciliationCandidates(@Param("now") java.time.Instant now,
        org.springframework.data.domain.Pageable page);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"reservation", "initiatedBy"})
    org.springframework.data.domain.Page<Payment> findAllByReservation_Customer_Id(
        UUID customerId, org.springframework.data.domain.Pageable pageable
    );

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findByGatewayIntentId(String gatewayIntentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment where payment.gatewayIntentId = :gatewayIntentId")
    Optional<Payment> findByGatewayIntentIdForUpdate(@Param("gatewayIntentId") String gatewayIntentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment where payment.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    Optional<Payment> findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
        UUID reservationId,
        PaymentType purpose
    );
    Optional<Payment> findTopByRenewal_IdAndPurposeOrderByCreatedAtDesc(UUID renewalId, PaymentType purpose);
    Optional<Payment> findTopByRenewal_IdAndRenewalRevision_IdAndPurposeOrderByCreatedAtDesc(
        UUID renewalId, UUID renewalRevisionId, PaymentType purpose);
    List<Payment> findAllByRenewal_IdAndStatusIn(UUID renewalId,
        Collection<com.storagehub.domain.model.PaymentStatus> statuses);
    List<Payment> findAllByRenewal_IdOrderByCreatedAtAsc(UUID renewalId);
    boolean existsByRenewal_IdAndStatusIn(UUID renewalId,
        Collection<com.storagehub.domain.model.PaymentStatus> statuses);
    Optional<Payment> findTopByReservation_IdAndPurposeAndGatewayTransactionNo(
        UUID reservationId,
        PaymentType purpose,
        String gatewayTransactionNo
    );
    List<Payment> findAllByReservation_IdAndStatusIn(
        UUID reservationId,
        Collection<com.storagehub.domain.model.PaymentStatus> statuses
    );
    List<Payment> findAllByReservation_IdOrderByCreatedAtAsc(UUID reservationId);

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

    @Query("""
        select p from Payment p
        left join p.reservation reservation
        left join p.renewal renewal
        left join renewal.rental rental
        where p.status = com.storagehub.domain.model.PaymentStatus.PAID
          and (:facilityId is null or reservation.facility.id = :facilityId or rental.facility.id = :facilityId)
        """)
    List<Payment> findPaidPaymentsByFacility(@Param("facilityId") UUID facilityId);

    List<Payment> findByStatus(com.storagehub.domain.model.PaymentStatus status);
}
