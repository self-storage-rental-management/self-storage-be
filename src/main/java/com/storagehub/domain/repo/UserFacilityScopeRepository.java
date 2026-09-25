package com.storagehub.domain.repo;

import com.storagehub.domain.model.UserFacilityScope;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFacilityScopeRepository extends JpaRepository<UserFacilityScope, UUID> {
    List<UserFacilityScope> findByUserId(UUID userId);
}
