package com.storagehub.api.rental;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.CustomerProfilePolicy;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Transactional(readOnly = true)
public class EmergencyContactsController {
    private final ActorContext actors;
    private final DuongResourceAccess access;
    private final EntityManager em;
    public record Contacts(UUID rentalId, String customerName, List<CustomerProfilePolicy.Contact> currentContacts, String contractSnapshot) {}

    @GetMapping("/api/rentals/{id}/emergency-contacts")
    public ApiResponse<List<Contacts>> rental(@PathVariable UUID id) {
        var rental = em.find(Rental.class, id);
        if (rental == null) throw ApiExceptions.notFound("Rental not found");
        authorize(rental.getFacility().getId(), false);
        return new ApiResponse<>(List.of(details(rental)), CorrelationIdContext.current());
    }
    @GetMapping("/api/storage-units/{id}/emergency-contacts")
    public ApiResponse<List<Contacts>> unit(@PathVariable UUID id) {
        var unit = em.find(StorageUnit.class, id);
        if (unit == null) throw ApiExceptions.notFound("Unit not found");
        authorize(unit.getFacility().getId(), true);
        var rentals = em.createQuery("select r from Rental r where r.storageUnit.id=:id and r.status<>:completed", Rental.class)
            .setParameter("id", id).setParameter("completed", RentalStatus.completed).getResultList();
        return new ApiResponse<>(rentals.stream().map(this::details).toList(), CorrelationIdContext.current());
    }
    private void authorize(UUID facility, boolean maintenance) {
        var actor = actors.required();
        if (actor.hasRole(RoleCode.MANAGER))
            access.require(actor, RoleCode.MANAGER, facility, FacilityScopeLevel.READ,
                maintenance ? SystemPermission.MANAGE_STAFF_TASKS : SystemPermission.VIEW_RETURNS);
        else if (!maintenance)
            access.require(actor, RoleCode.STAFF, facility, FacilityScopeLevel.READ, SystemPermission.VIEW_RETURNS);
        else throw ApiExceptions.forbidden("Manager access required");
    }
    private Contacts details(Rental rental) {
        var contracts = em.createQuery("select c from Contract c where c.rental.id=:id", Contract.class)
            .setParameter("id", rental.getId()).setMaxResults(1).getResultList();
        String snapshot = contracts.isEmpty() ? null : contracts.getFirst().getEmergencyContactsSnapshot();
        return new Contacts(rental.getId(), rental.getCustomer().getFullName(), CustomerProfilePolicy.contacts(rental.getCustomer()), snapshot);
    }
}
