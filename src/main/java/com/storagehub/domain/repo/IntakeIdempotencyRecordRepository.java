package com.storagehub.domain.repo;

import com.storagehub.domain.model.IntakeIdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeIdempotencyRecordRepository extends JpaRepository<IntakeIdempotencyRecord, UUID> {
    Optional<IntakeIdempotencyRecord> findByRental_IdAndActionAndIdempotencyKey(UUID rentalId, String action, String idempotencyKey);
}
