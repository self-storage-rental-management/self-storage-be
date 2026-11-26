package com.storagehub.domain.repo;

import com.storagehub.domain.model.IntakeVerificationAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeVerificationAttemptRepository extends JpaRepository<IntakeVerificationAttempt, UUID> {
    List<IntakeVerificationAttempt> findByRental_IdOrderByRecordedAtAsc(UUID rentalId);
    Optional<IntakeVerificationAttempt> findByRental_IdAndIdempotencyKey(UUID rentalId, String idempotencyKey);
}
