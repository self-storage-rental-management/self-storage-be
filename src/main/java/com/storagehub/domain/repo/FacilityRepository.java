package com.storagehub.domain.repo;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {

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
