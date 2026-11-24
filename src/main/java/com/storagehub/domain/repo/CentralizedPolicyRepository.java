package com.storagehub.domain.repo;

import com.storagehub.domain.model.CentralizedPolicy;
import com.storagehub.domain.model.PublicationStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CentralizedPolicyRepository extends JpaRepository<CentralizedPolicy, UUID> {
    Optional<CentralizedPolicy> findByPolicyVersion(String policyVersion);
    List<CentralizedPolicy> findAllByOrderByCreatedAtDesc();
    List<CentralizedPolicy> findByPublicationStatusOrderByCreatedAtDesc(PublicationStatus status);
    Optional<CentralizedPolicy> findFirstByPublicationStatusOrderByCreatedAtDesc(PublicationStatus status);

    @Query("SELECT p FROM CentralizedPolicy p WHERE p.active = true AND p.publicationStatus = 'PUBLISHED' " +
           "AND p.effectiveFrom <= :date AND (p.effectiveTo IS NULL OR p.effectiveTo >= :date) " +
           "ORDER BY p.effectiveFrom DESC, p.createdAt DESC")
    List<CentralizedPolicy> findActivePoliciesForDate(@Param("date") LocalDate date);

    @Query("SELECT p FROM CentralizedPolicy p WHERE p.active = true AND p.publicationStatus = 'PUBLISHED' " +
           "ORDER BY p.effectiveFrom DESC, p.createdAt DESC")
    List<CentralizedPolicy> findActivePolicies();
}
