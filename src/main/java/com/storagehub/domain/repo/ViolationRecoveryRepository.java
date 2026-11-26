package com.storagehub.domain.repo;

import com.storagehub.domain.model.ViolationRecovery;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ViolationRecoveryRepository extends JpaRepository<ViolationRecovery, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ViolationRecovery v where v.id = :id")
    Optional<ViolationRecovery> lock(@Param("id") UUID id);

    List<ViolationRecovery> findByRental_IdOrderByCreatedAtDesc(UUID rentalId);
    List<ViolationRecovery> findBySourceReturnIdOrderByCreatedAtDesc(UUID sourceReturnId);
    List<ViolationRecovery> findByRental_Customer_IdOrderByCreatedAtDesc(UUID customerId, Pageable page);
    List<ViolationRecovery> findByTask_AssignedTo_IdOrderByCreatedAtDesc(UUID staffId, Pageable page);
    List<ViolationRecovery> findByRental_Facility_IdInOrderByCreatedAtDesc(java.util.Collection<UUID> facilities, Pageable page);
    List<ViolationRecovery> findAllByOrderByCreatedAtDesc(Pageable page);

    @Query("""
        select v.id from ViolationRecovery v
        where v.actionDueAt <= :now or v.responseCheckAt <= :now
           or (v.deadlineAt <= :now and v.overdueAt is null and v.completedAt is null and v.stage <> 'CANCELLED')
           or (v.pickupReviewAt <= :now and v.pickupEscalated = false)
        order by v.updatedAt asc
        """)
    List<UUID> due(@Param("now") Instant now, Pageable page);
}
