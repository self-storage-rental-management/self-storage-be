package com.storagehub.domain.repo;

import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitTypeRepository extends JpaRepository<UnitType, UUID> {

    @Query("""
        select unitType from UnitType unitType
        where unitType.facility.id = :facilityId
          and (:status is null or unitType.status = :status)
        """)
    Page<UnitType> search(
        @Param("facilityId") UUID facilityId,
        @Param("status") UnitTypeStatus status,
        Pageable pageable
    );
}
