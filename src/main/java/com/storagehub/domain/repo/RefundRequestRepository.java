package com.storagehub.domain.repo;

import com.storagehub.domain.model.RefundRequest;
import com.storagehub.domain.model.RefundRequestStatus;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {
    @Query("select r from RefundRequest r where r.status = :status order by r.createdAt asc, r.id asc")
    List<RefundRequest> findByStatusOrderByCreatedAtAsc(@Param("status") RefundRequestStatus status);

    List<RefundRequest> findByPayment_IdAndStatusIn(UUID paymentId, Collection<RefundRequestStatus> statuses);

    @Query("""
        select coalesce(sum(r.amount), 0) from RefundRequest r
        where r.payment.id = :paymentId
          and r.status in :statuses
        """)
    BigDecimal sumAmountsByPaymentIdAndStatusIn(
        @Param("paymentId") UUID paymentId,
        @Param("statuses") Collection<RefundRequestStatus> statuses
    );

    @Query("""
        select count(r) from RefundRequest r
        join r.payment p
        left join p.reservation reservation
        left join p.renewal renewal
        left join renewal.rental rental
        where (reservation.facility.id = :facilityId or rental.facility.id = :facilityId)
          and r.status in :statuses
        """)
    long countOutstandingByFacilityId(
        @Param("facilityId") UUID facilityId,
        @Param("statuses") Collection<RefundRequestStatus> statuses
    );
}
