package com.storagehub.domain.repo;

import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorageUnitRepository extends JpaRepository<StorageUnit, UUID> {

    @Query("""
        select u from StorageUnit u
        where (:facilityId is null or u.facility.id = :facilityId)
          and (:status is null or u.status = :status)
          and (:unitTypeId is null or u.unitType.id = :unitTypeId)
        """)
    Page<StorageUnit> search(
        @Param("facilityId") UUID facilityId,
        @Param("status") StorageUnitStatus status,
        @Param("unitTypeId") UUID unitTypeId,
        Pageable pageable
    );
}
