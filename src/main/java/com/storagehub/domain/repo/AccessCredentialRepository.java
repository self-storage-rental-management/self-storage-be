package com.storagehub.domain.repo;

import com.storagehub.domain.model.AccessCredential;
import com.storagehub.domain.model.AccessCredentialStatus;
import com.storagehub.domain.model.AccessCredentialType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AccessCredentialRepository extends JpaRepository<AccessCredential, UUID> {
    List<AccessCredential> findByReservation_IdAndStatus(UUID reservationId, AccessCredentialStatus status);

    List<AccessCredential> findByRental_IdAndStatus(UUID rentalId, AccessCredentialStatus status);

    List<AccessCredential> findByFacility_IdAndTypeAndReferenceIgnoreCaseOrderByIssuedAtDesc(
        UUID facilityId, AccessCredentialType type, String reference
    );

    boolean existsByReservation_IdAndStatus(UUID reservationId, AccessCredentialStatus status);
    boolean existsByRental_IdAndStatus(UUID rentalId, AccessCredentialStatus status);

    boolean existsByFacility_IdAndStatusAndReferenceIgnoreCaseAndIdNot(
        UUID facilityId, AccessCredentialStatus status, String reference, UUID id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c from AccessCredential c
        where c.facility.id = :facilityId
          and lower(trim(c.reference)) = lower(:reference)
          and (c.status = com.storagehub.domain.model.AccessCredentialStatus.ACTIVE or c.lockCodeHeld = true)
        """)
    List<AccessCredential> findLockCodeConflictsForUpdate(@Param("facilityId") UUID facilityId, @Param("reference") String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AccessCredential> findByRental_IdAndStatusOrderById(UUID rentalId, AccessCredentialStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccessCredential> findFirstByRental_IdAndTypeOrderByIssuedAtDescIdDesc(
        UUID rentalId, AccessCredentialType type
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AccessCredential c where c.id = :id")
    Optional<AccessCredential> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select c from AccessCredential c
        where (:facilityId is null or c.facility.id = :facilityId)
          and (:statuses is null or c.status in :statuses)
          and (:scoped = false or c.facility.id in :facilityIds)
          and (:q is null or :q = ''
            or lower(c.reference) like lower(concat('%', :q, '%'))
            or lower(c.storageUnit.code) like lower(concat('%', :q, '%'))
            or lower(c.customer.fullName) like lower(concat('%', :q, '%'))
            or lower(c.customer.email) like lower(concat('%', :q, '%'))
            or lower(c.reservation.reservationCode) like lower(concat('%', :q, '%')))
        """)
    Page<AccessCredential> search(
        @Param("facilityId") UUID facilityId,
        @Param("statuses") Collection<AccessCredentialStatus> statuses,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") Collection<UUID> facilityIds,
        @Param("q") String q,
        Pageable pageable
    );
}
