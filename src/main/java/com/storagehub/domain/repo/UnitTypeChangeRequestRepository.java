package com.storagehub.domain.repo;

import com.storagehub.domain.model.UnitTypeChangeRequest;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface UnitTypeChangeRequestRepository extends JpaRepository<UnitTypeChangeRequest, UUID> {
    boolean existsByUnitTypeIdAndStatus(UUID unitTypeId, String status);
    List<UnitTypeChangeRequest> findTop100ByOrderByCreatedAtDesc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from UnitTypeChangeRequest r where r.id = :id")
    Optional<UnitTypeChangeRequest> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);
}
