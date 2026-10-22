package com.storagehub.api.rental;

import com.storagehub.domain.model.Contract;
import com.storagehub.domain.repo.ContractRepository;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.rental.RentalContractLifecycleService;
import com.storagehub.service.rental.RentalContractLifecycleService.LifecycleExecutionSummary;
import com.storagehub.service.rental.RentalContractLifecycleService.PostExpirationEligibility;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rentals/lifecycle")
public class RentalContractLifecycleController {

    private final RentalContractLifecycleService lifecycleService;
    private final ContractRepository contractRepository;
    private final ActorContext actorContext;

    public RentalContractLifecycleController(
        RentalContractLifecycleService lifecycleService,
        ContractRepository contractRepository,
        ActorContext actorContext
    ) {
        this.lifecycleService = lifecycleService;
        this.contractRepository = contractRepository;
        this.actorContext = actorContext;
    }

    public record TerminateContractRequest(String reason) {}

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processLifecycle() {
        actorContext.required();
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
        actorContext.required();
        return ResponseEntity.ok(lifecycleService.checkPostExpirationRenewalEligibility(rentalId));
    }

    @GetMapping("/contracts/{rentalId}")
    public ResponseEntity<List<Contract>> getRentalContracts(
        @PathVariable UUID rentalId
    ) {
        actorContext.required();
        return ResponseEntity.ok(contractRepository.findAllByRentalIdOrderByCreatedAtDesc(rentalId));
    }

    @PostMapping("/contracts/{contractId}/terminate")
    public ResponseEntity<Contract> terminateContract(
        @PathVariable UUID contractId,
        @RequestBody(required = false) TerminateContractRequest request
    ) {
        ActorPrincipal actor = actorContext.required();
        String reason = request != null ? request.reason() : "Chấm dứt trước hạn theo quy trình";
        Contract contract = lifecycleService.terminateContract(actor, contractId, reason);
        return ResponseEntity.ok(contract);
    }
}
