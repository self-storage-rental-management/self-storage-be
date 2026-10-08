package com.storagehub.domain.repo;

import com.storagehub.domain.model.UserFacilityScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFacilityScopeRepository extends JpaRepository<UserFacilityScope, UUID> {
    List<UserFacilityScope> findByUserId(UUID userId);
    Optional<UserFacilityScope> findByUser_IdAndFacility_Id(UUID userId, UUID facilityId);

    @Modifying
    @Query("delete from UserFacilityScope scope where scope.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
