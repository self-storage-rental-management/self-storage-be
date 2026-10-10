package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only D3 assignment projection. Does not change Booking/Check-in assignment or authorization. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalAssignmentReadService {
    private final EntityManager em;
    private final DuongResourceAccess access;
    private final RenewalAssignmentSource assignments;
    public record View(UUID renewalId, Long expectedVersion, String status, UUID staffId, String staffName) {}
    public View read(ActorPrincipal actor, UUID id) {
        access.require(actor, RoleCode.MANAGER, null, FacilityScopeLevel.READ, SystemPermission.VIEW_RENTALS);
        var n = em.find(Renewal.class, id);
        if (n == null || n.getRental() == null) throw ApiExceptions.notFound("Renewal not found");
        UUID facility = n.getRental().getFacility().getId();
        access.require(actor, RoleCode.MANAGER, facility, FacilityScopeLevel.READ, SystemPermission.VIEW_RENTALS);
        var w = em.find(RenewalWorkflow.class, id);
        Long version = w == null ? null : w.getVersion();
        if (w == null || w.getAcceptedRevision() == null) return new View(id, version, "UNAVAILABLE", null, null);
        java.util.Optional<RenewalAssignmentSource.Assignment> current;
        try { current = assignments.current(n); }
        catch (ApiException e) {
            if (e.getStatus() != org.springframework.http.HttpStatus.CONFLICT) throw e;
            return new View(id, version, "UNAVAILABLE", null, null);
        }
        if (current.isEmpty()) return new View(id, version, "UNASSIGNED", null, null);
        var a = current.get(); var staff = em.find(User.class, a.staffId());
        boolean eligible = access.eligible(staff, RoleCode.STAFF, SystemPermission.VIEW_RENTALS, SystemPermission.MANAGE_RENTALS)
            && access.scope(a.staffId(), facility, FacilityScopeLevel.OPERATE);
        return new View(id, version, eligible ? "ASSIGNED" : "INELIGIBLE", a.staffId(), staff == null || staff.getFullName() == null || staff.getFullName().isBlank() ? null : staff.getFullName());
    }
}
