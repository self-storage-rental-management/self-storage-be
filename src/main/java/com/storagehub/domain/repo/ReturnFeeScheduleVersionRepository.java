package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReturnFeeScheduleVersion;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface ReturnFeeScheduleVersionRepository extends JpaRepository<ReturnFeeScheduleVersion, Long> {
    Optional<ReturnFeeScheduleVersion> findFirstByOrderByRevisionDesc();

    Optional<ReturnFeeScheduleVersion> findFirstByPublishedAtLessThanOrderByPublishedAtDescRevisionDesc(Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ReturnFeeScheduleVersion v where v.revision = 0")
    Optional<ReturnFeeScheduleVersion> lockBaseline();
}
