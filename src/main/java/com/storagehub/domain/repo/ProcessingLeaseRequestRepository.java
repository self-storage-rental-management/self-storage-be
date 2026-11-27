package com.storagehub.domain.repo;

import com.storagehub.domain.model.ProcessingLeaseRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingLeaseRequestRepository extends JpaRepository<ProcessingLeaseRequest, UUID> {
    List<ProcessingLeaseRequest> findByReservationIdOrderByRequestedAtAsc(UUID reservationId);
}
