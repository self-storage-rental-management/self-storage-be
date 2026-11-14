package com.storagehub.api.rental;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Contract;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.ContractRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AdminAuthorizationService;
import com.storagehub.service.FacilityScopeService;
import com.storagehub.service.rental.RentalContractLifecycleService;
import com.storagehub.service.rental.RentalContractLifecycleService.LifecycleExecutionSummary;
import com.storagehub.service.rental.RentalContractLifecycleService.PostExpirationEligibility;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rentals/lifecycle")
public class RentalContractLifecycleController {

    private final RentalContractLifecycleService lifecycleService;
    private final ContractRepository contractRepository;
    private final RentalRepository rentalRepository;
    private final ActorContext actorContext;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;

    @Autowired
    public RentalContractLifecycleController(
        RentalContractLifecycleService lifecycleService,
        ContractRepository contractRepository,
        RentalRepository rentalRepository,
        ActorContext actorContext,
        AdminAuthorizationService authorizationService,
        FacilityScopeService facilityScopeService
    ) {
        this.lifecycleService = lifecycleService;
        this.contractRepository = contractRepository;
        this.rentalRepository = rentalRepository;
        this.actorContext = actorContext;
        this.authorizationService = authorizationService;
        this.facilityScopeService = facilityScopeService;
    }

    public record TerminateContractRequest(String reason) {}

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processLifecycle() {
        ActorPrincipal actor = actorContext.required();
        if (!actor.hasRole(RoleCode.ADMIN) && !actor.hasRole(RoleCode.MANAGER)) {
            throw ApiExceptions.forbidden("Only Admin or Manager can process contract lifecycles");
        }
        if (actor.hasRole(RoleCode.MANAGER)) {
            authorizationService.require(actor, SystemPermission.MANAGE_RENTALS);
        }
        LifecycleExecutionSummary exp = lifecycleService.processContractExpirations();
        LifecycleExecutionSummary pay = lifecycleService.processLatePayments();
        return ResponseEntity.ok(Map.of(
            "success", true,
            "expirations", exp,
            "latePayments", pay
        ));
    }

    @GetMapping("/eligibility/{rentalId}")
    public ResponseEntity<PostExpirationEligibility> checkEligibility(
        @PathVariable UUID rentalId
    ) {
        ActorPrincipal actor = actorContext.required();
        assertCanReadRental(actor, rentalId);
        return ResponseEntity.ok(lifecycleService.checkPostExpirationRenewalEligibility(rentalId));
    }

    @GetMapping("/contracts/{rentalId}")
    public ResponseEntity<List<Contract>> getRentalContracts(
        @PathVariable UUID rentalId
    ) {
        ActorPrincipal actor = actorContext.required();
        assertCanReadRental(actor, rentalId);
        return ResponseEntity.ok(contractRepository.findAllByRentalIdOrderByCreatedAtDesc(rentalId));
    }

    @PostMapping("/contracts/{contractId}/terminate")
    public ResponseEntity<Contract> terminateContract(
        @PathVariable UUID contractId,
        @RequestBody(required = false) TerminateContractRequest request
    ) {
        ActorPrincipal actor = actorContext.required();
        if (actor.hasRole(RoleCode.CUSTOMER) || (!actor.hasRole(RoleCode.ADMIN) && !actor.hasRole(RoleCode.MANAGER) && !actor.hasRole(RoleCode.STAFF))) {
            throw ApiExceptions.forbidden("Only authorized facility operators can terminate contracts");
        }
        authorizationService.require(actor, SystemPermission.MANAGE_RENTALS);
        Contract existing = contractRepository.findById(contractId)
            .orElseThrow(() -> ApiExceptions.notFound("Contract was not found"));
        if (existing.getRental() != null && existing.getRental().getFacility() != null) {
            facilityScopeService.assertCanOperate(actor, existing.getRental().getFacility().getId());
        }
        String reason = request != null ? request.reason() : "Chấm dứt trước hạn theo quy trình";
        Contract contract = lifecycleService.terminateContract(actor, contractId, reason);
        return ResponseEntity.ok(contract);
    }

    private void assertCanReadRental(ActorPrincipal actor, UUID rentalId) {
        Rental rental = rentalRepository.findById(rentalId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found"));
        if (actor.hasRole(RoleCode.CUSTOMER)) {
            if (rental.getCustomer() == null || !rental.getCustomer().getId().equals(actor.userId())) {
                throw ApiExceptions.notFound("Rental was not found");
            }
            return;
        }
        authorizationService.require(actor, SystemPermission.VIEW_RENTALS);
        if (rental.getFacility() != null) {
            facilityScopeService.assertCanRead(actor, rental.getFacility().getId());
        }
    }
}
