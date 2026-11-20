package com.storagehub.domain.repo;

import com.storagehub.domain.model.FacilityLifecycleChangeRequest;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityLifecycleChangeRequestRepository extends JpaRepository<FacilityLifecycleChangeRequest, UUID> {
    boolean existsByFacilityIdAndStatus(UUID facilityId, String status);

    List<FacilityLifecycleChangeRequest> findTop100ByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FacilityLifecycleChangeRequest r where r.id = :id")
    Optional<FacilityLifecycleChangeRequest> findLockedById(@Param("id") UUID id);
}
