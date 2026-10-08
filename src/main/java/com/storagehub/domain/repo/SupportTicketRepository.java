package com.storagehub.domain.repo;

import com.storagehub.domain.model.SupportTicket;
import com.storagehub.domain.model.SupportTicketStatus;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    Page<SupportTicket> findByCustomer_Id(UUID customerId, Pageable pageable);

    Page<SupportTicket> findByCustomer_IdAndStatus(
        UUID customerId,
        SupportTicketStatus status,
        Pageable pageable
    );

    @Query("""
        select t from SupportTicket t
        where (:status is null or t.status = :status)
          and (:facilityId is null or t.facility.id = :facilityId)
          and (:scoped = false or t.facility is null or t.facility.id in :allowedFacilityIds)
          and (
            :q is null or :q = ''
            or lower(t.subject) like lower(concat('%', :q, '%'))
            or lower(t.description) like lower(concat('%', :q, '%'))
            or lower(t.customer.fullName) like lower(concat('%', :q, '%'))
            or lower(t.customer.email) like lower(concat('%', :q, '%'))
          )
        """)
    Page<SupportTicket> searchForStaff(
        @Param("status") SupportTicketStatus status,
        @Param("facilityId") UUID facilityId,
        @Param("scoped") boolean scoped,
        @Param("allowedFacilityIds") Collection<UUID> allowedFacilityIds,
        @Param("q") String query,
        Pageable pageable
    );
}
