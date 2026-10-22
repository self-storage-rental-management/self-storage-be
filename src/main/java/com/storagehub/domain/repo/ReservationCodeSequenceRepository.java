package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReservationCodeSequence;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ReservationCodeSequenceRepository extends JpaRepository<ReservationCodeSequence, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sequence from ReservationCodeSequence sequence where sequence.id = 1")
    Optional<ReservationCodeSequence> findPrimaryForUpdate();
}
