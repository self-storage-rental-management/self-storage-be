package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnCaseRepository extends JpaRepository<ReturnCase, UUID> {

    Optional<ReturnCase> findByIdAndCustomer_Id(UUID id, UUID customerId);

    Page<ReturnCase> findByCustomer_Id(UUID customerId, Pageable pageable);

    Optional<ReturnCase> findByRental_Id(UUID rentalId);

    boolean existsByRental_IdAndStatusNotIn(UUID rentalId, Collection<ReturnCaseStatus> statuses);

    @Query("""
        select rc from ReturnCase rc
        where (:facilityId is null or rc.facility.id = :facilityId)
          and (:status is null or rc.status = :status)
          and (:allowedFacilityIds is null or rc.facility.id in :allowedFacilityIds)
          and (
            :q is null or :q = ''
            or lower(rc.customer.fullName) like lower(concat('%', :q, '%'))
            or lower(rc.customer.email) like lower(concat('%', :q, '%'))
            or lower(rc.customer.phone) like lower(concat('%', :q, '%'))
            or lower(rc.storageUnit.code) like lower(concat('%', :q, '%'))
            or lower(rc.facility.name) like lower(concat('%', :q, '%'))
          )
        """)
    Page<ReturnCase> searchReturnCases(
        @Param("facilityId") UUID facilityId,
        @Param("status") ReturnCaseStatus status,
        @Param("allowedFacilityIds") Collection<UUID> allowedFacilityIds,
        @Param("q") String q,
        Pageable pageable
    );
}
