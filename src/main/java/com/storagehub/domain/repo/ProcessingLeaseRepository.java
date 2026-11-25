package com.storagehub.domain.repo;

import com.storagehub.domain.model.ProcessingLease;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingLeaseRepository extends JpaRepository<ProcessingLease, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select lease from ProcessingLease lease where lease.reservationId = :id")
    java.util.Optional<ProcessingLease> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
