package com.storagehub.domain.repo;

import com.storagehub.domain.model.RentalPackagePolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalPackagePolicyRepository extends JpaRepository<RentalPackagePolicy, UUID> {
    @EntityGraph(attributePaths = {"facility"})
    Optional<RentalPackagePolicy> findById(UUID id);

    @EntityGraph(attributePaths = {"facility"})
    Optional<RentalPackagePolicy> findByFacility_IdAndCode(UUID facilityId, String code);

    @EntityGraph(attributePaths = {"facility"})
    List<RentalPackagePolicy> findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
        UUID facilityId,
        LocalDate date
    );

    @EntityGraph(attributePaths = {"facility"})
    List<RentalPackagePolicy> findByFacility_IdOrderByRentalMonthsAsc(UUID facilityId);

    @EntityGraph(attributePaths = {"facility"})
    List<RentalPackagePolicy> findByFacility_IdAndRentalMonths(UUID facilityId, int rentalMonths);

    @EntityGraph(attributePaths = {"facility"})
    List<RentalPackagePolicy> findAllByOrderByRentalMonthsAsc();

    @EntityGraph(attributePaths = {"facility"})
    List<RentalPackagePolicy> findAll();
}
