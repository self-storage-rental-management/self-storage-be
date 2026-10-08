package com.storagehub.domain.repo;

import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitTypeRepository extends JpaRepository<UnitType, UUID> {

    java.util.Optional<UnitType> findByFacility_IdAndCode(UUID facilityId, String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select unitType from UnitType unitType where unitType.id = :id")
    java.util.Optional<UnitType> findByIdForUpdate(@Param("id") UUID id);

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
