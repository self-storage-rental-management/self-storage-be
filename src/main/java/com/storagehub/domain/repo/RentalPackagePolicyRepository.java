package com.storagehub.domain.repo;

import com.storagehub.domain.model.RentalPackagePolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalPackagePolicyRepository extends JpaRepository<RentalPackagePolicy, UUID> {
    Optional<RentalPackagePolicy> findByFacility_IdAndCode(UUID facilityId, String code);

    List<RentalPackagePolicy> findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
        UUID facilityId,
        LocalDate date
    );

    List<RentalPackagePolicy> findByFacility_IdOrderByRentalMonthsAsc(UUID facilityId);

    List<RentalPackagePolicy> findAllByOrderByRentalMonthsAsc();
}
