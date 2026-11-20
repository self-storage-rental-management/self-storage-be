package com.storagehub.domain.repo;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {

    interface PublicContact {
        String getCode();
        String getName();
        String getCity();
        String getPhone();
        String getAccessHours();
    }

    @Query("""
        select f.code as code, f.name as name, f.city as city, f.phone as phone, f.accessHours as accessHours
        from Facility f where f.status = :status order by f.name, f.code
        """)
    List<PublicContact> findPublicContacts(@Param("status") FacilityStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Facility f where f.id = :id")
    Optional<Facility> findByIdForUpdate(@Param("id") UUID id);

    java.util.Optional<Facility> findByCodeIgnoreCase(String code);

    @Query("""
        select f from Facility f
        where (:status is null or f.status = :status)
          and (:city is null or lower(f.city) like lower(concat('%', :city, '%')))
          and (:query is null or lower(f.name) like lower(concat('%', :query, '%'))
               or lower(f.code) like lower(concat('%', :query, '%')))
          and (:scoped = false or f.id in :facilityIds)
        """)
    Page<Facility> search(
        @Param("status") FacilityStatus status,
        @Param("city") String city,
        @Param("query") String query,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") Collection<UUID> facilityIds,
        Pageable pageable
    );

    Page<Facility> findByStatus(FacilityStatus status, Pageable pageable);
}
