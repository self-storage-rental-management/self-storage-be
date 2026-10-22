package com.storagehub.domain.repo;

import com.storagehub.domain.model.DiscountCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiscountCodeRepository extends JpaRepository<DiscountCode, UUID> {

    Optional<DiscountCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<DiscountCode> findByFacility_IdOrderByCreatedAtDesc(UUID facilityId);

    List<DiscountCode> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE DiscountCode d SET d.currentUses = d.currentUses + 1 WHERE d.id = :id AND (d.maxUses IS NULL OR d.currentUses < d.maxUses)")
    int incrementUsageIfAvailable(@Param("id") UUID id);
}
