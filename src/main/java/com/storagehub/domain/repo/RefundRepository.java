package com.storagehub.domain.repo;

import com.storagehub.domain.model.Refund;
import java.util.UUID;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    @Query("""
        select count(r) from Refund r
        join r.payment p
        left join p.reservation reservation
        left join p.renewal renewal
        left join renewal.rental rental
        where (reservation.facility.id = :facilityId or rental.facility.id = :facilityId)
          and r.status in :statuses
        """)
    long countOutstandingByFacilityId(
        @Param("facilityId") UUID facilityId,
        @Param("statuses") Collection<com.storagehub.domain.model.RefundStatus> statuses
    );
}
