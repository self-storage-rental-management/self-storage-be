package com.storagehub.api.checkin;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.CheckInHandoverService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/check-ins")
@RequiredArgsConstructor
public class CheckInController {

    private final ActorContext actorContext;
    private final CheckInHandoverService service;

    @GetMapping("/{checkInId}/inventory-alternatives")
    public ApiResponse<java.util.List<CheckInHandoverService.InventoryAlternative>> inventoryAlternatives(@PathVariable UUID checkInId) {
        return new ApiResponse<>(service.inventoryAlternatives(actorContext.required(), checkInId), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/identity-correction")
    public ApiResponse<CheckInResponse> correctIdentity(@PathVariable UUID checkInId, @Valid @RequestBody VerifyCheckInIdentityRequest request) {
        return new ApiResponse<>(service.correctIdentity(actorContext.required(), checkInId, request), CorrelationIdContext.current());
    }

    @GetMapping
    public PageResponse<CheckInResponse> list(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "ALL") CheckInHandoverService.WorkGroup group,
        @RequestParam(required = false) Instant scheduledFrom,
        @RequestParam(required = false) Instant scheduledTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return service.list(
            actorContext.required(), facilityId, q, status, group, scheduledFrom, scheduledTo,
            page, pageSize,
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/reservations/{reservationId}/schedule")
    public ApiResponse<CheckInResponse> schedule(
        @PathVariable UUID reservationId,
        @Valid @RequestBody ScheduleCheckInRequest request
    ) {
        return new ApiResponse<>(
            service.schedule(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/{checkInId}/inventory-draft")
    public ApiResponse<InventoryDraftResponse> inventoryDraft(@PathVariable UUID checkInId) {
        return new ApiResponse<>(service.getInventoryDraft(actorContext.required(), checkInId), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/inventory-draft")
    public ApiResponse<InventoryDraftResponse> saveInventoryDraft(@PathVariable UUID checkInId,
        @Valid @RequestBody SaveInventoryDraftRequest request) {
        return new ApiResponse<>(service.saveInventoryDraft(actorContext.required(), checkInId, request), CorrelationIdContext.current());
    }

    @PostMapping({"/{checkInId}/inventory-draft/publish", "/{checkInId}/inventory-draft/resolve"})
    public ApiResponse<CheckInResponse> publishInventoryDraft(@PathVariable UUID checkInId,
        @Valid @RequestBody PublishInventoryDraftRequest request) {
        return new ApiResponse<>(service.publishInventoryDraft(actorContext.required(), checkInId, request.expectedRevision()), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/inventory-resolution-at-site")
    public ApiResponse<CheckInResponse> resolveInventoryAtSite(@PathVariable UUID checkInId,
        @Valid @RequestBody ResolveInventoryAtSiteRequest request) {
        return new ApiResponse<>(service.resolveInventoryAtSite(actorContext.required(), checkInId, request), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/inventory-return-reminder")
    public ApiResponse<CheckInResponse> sendInventoryReturnReminder(@PathVariable UUID checkInId) {
        return new ApiResponse<>(service.sendInventoryReturnReminder(actorContext.required(), checkInId), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/complete")
    public ApiResponse<CheckInResponse> complete(
        @PathVariable UUID checkInId,
        @Valid @RequestBody CompleteCheckInRequest request
    ) {
        return new ApiResponse<>(
            service.complete(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/inventory-arrival")
    public ApiResponse<CheckInResponse> recordInventoryArrival(@PathVariable UUID checkInId) {
        return new ApiResponse<>(service.recordInventoryArrival(actorContext.required(), checkInId),
            CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/inventory-identity")
    public ApiResponse<CheckInResponse> verifyInventoryIdentity(
        @PathVariable UUID checkInId, @Valid @RequestBody VerifyInventoryIdentityRequest request
    ) {
        return new ApiResponse<>(service.verifyInventoryIdentity(actorContext.required(), checkInId, request),
            CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/identity-comparison")
    public ApiResponse<CheckInHandoverService.IdentityComparison> compareIdentity(
        @PathVariable UUID checkInId,
        @Valid @RequestBody VerifyCheckInIdentityRequest request
    ) {
        return new ApiResponse<>(service.compareIdentity(actorContext.required(), checkInId, request.identityDocumentNumber()),
            CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/identity-verification")
    public ApiResponse<CheckInResponse> verifyIdentity(
        @PathVariable UUID checkInId,
        @Valid @RequestBody VerifyCheckInIdentityRequest request
    ) {
        return new ApiResponse<>(
            service.verifyIdentity(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/contract-signing")
    public ApiResponse<CheckInResponse> signContract(
        @PathVariable UUID checkInId,
        @Valid @RequestBody SignCheckInContractRequest request
    ) {
        return new ApiResponse<>(
            service.signContract(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/payment-confirmation")
    public ApiResponse<CheckInResponse> confirmNoRemainingPayment(
        @PathVariable UUID checkInId,
        @Valid @RequestBody ConfirmCheckInPaymentRequest request
    ) {
        return new ApiResponse<>(
            service.confirmNoRemainingPayment(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/setup/start")
    public ApiResponse<CheckInResponse> startSetup(
        @PathVariable UUID checkInId,
        @Valid @RequestBody StartCheckInSetupRequest request
    ) {
        return new ApiResponse<>(
            service.startSetup(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/receipt-reminder")
    public ApiResponse<ReceiptReminderResponse> remindCustomerReceipt(
        @PathVariable UUID checkInId
    ) {
        return new ApiResponse<>(
            service.remindCustomerReceipt(actorContext.required(), checkInId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/receipt-release")
    public ApiResponse<CheckInResponse> releaseAfterCustomerReceiptTimeout(
        @PathVariable UUID checkInId
    ) {
        return new ApiResponse<>(
            service.releaseAfterCustomerReceiptTimeout(actorContext.required(), checkInId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/setup/complete")
    public ApiResponse<CheckInResponse> completeSetup(
        @PathVariable UUID checkInId,
        @Valid @RequestBody CompleteCheckInSetupRequest request
    ) {
        return new ApiResponse<>(
            service.completeSetup(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/remaining-payment")
    public ApiResponse<CheckInPaymentResponse> recordRemainingPayment(
        @PathVariable UUID checkInId,
        @Valid @RequestBody CheckInPaymentRequest request
    ) {
        return new ApiResponse<>(
            service.recordRemainingPayment(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    public record ArrivalReview(@jakarta.validation.constraints.NotNull java.time.Instant actualArrivedAt,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String evidence, boolean disputed) {
        public ArrivalReview(java.time.Instant actualArrivedAt, String evidence) { this(actualArrivedAt, evidence, false); }
    }

    @GetMapping("/arrival-reviews")
    public ApiResponse<java.util.List<CheckInHandoverService.PendingArrival>> arrivalReviews() {
        return new ApiResponse<>(service.pendingArrivals(actorContext.required()), CorrelationIdContext.current());
    }

    public record ResolveArrival(@jakarta.validation.constraints.NotNull Boolean accepted,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {}

    public record SubmitRefusal(
        @jakarta.validation.constraints.NotNull Boolean customerPresent,
        @jakarta.validation.constraints.NotNull UUID witnessId,
        @jakarta.validation.constraints.NotNull UUID evidenceId,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1500) String grounds,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1500) String discussion,
        @jakarta.validation.constraints.Size(max = 1000) String customerReason,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long resultVersion
    ) {}

    public record ReviewRefusal(@jakarta.validation.constraints.NotNull java.time.Instant submittedAt,
        @jakarta.validation.constraints.NotNull Boolean approved,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String reason) {}

    @GetMapping("/{checkInId}/refusal-witnesses")
    public ApiResponse<java.util.List<CheckInHandoverService.RefusalWitness>> refusalWitnesses(@PathVariable UUID checkInId) {
        return new ApiResponse<>(service.refusalWitnesses(actorContext.required(), checkInId), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/signature-refusal")
    public ApiResponse<CheckInResponse> signatureRefusal(@PathVariable UUID checkInId, @Valid @RequestBody SubmitRefusal request) {
        return new ApiResponse<>(service.submitInventoryRefusal(actorContext.required(), checkInId, request), CorrelationIdContext.current());
    }

    @GetMapping("/signature-refusal-reviews")
    public ApiResponse<java.util.List<CheckInHandoverService.PendingRefusal>> signatureRefusalReviews() {
        return new ApiResponse<>(service.pendingInventoryRefusals(actorContext.required()), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/signature-refusal-review")
    public ApiResponse<CheckInResponse> signatureRefusalReview(@PathVariable UUID checkInId, @Valid @RequestBody ReviewRefusal request) {
        return new ApiResponse<>(service.reviewInventoryRefusal(actorContext.required(), checkInId,
            request.submittedAt(), request.approved(), request.reason()), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/arrival-review")
    public ApiResponse<CheckInResponse> resolveArrival(@PathVariable UUID checkInId,
        @Valid @RequestBody ResolveArrival decision) {
        return new ApiResponse<>(service.resolveArrival(actorContext.required(), checkInId,
            decision.accepted(), decision.reason()), CorrelationIdContext.current());
    }

    @PostMapping("/{checkInId}/arrival")
    public ApiResponse<CheckInResponse> recordArrival(@PathVariable UUID checkInId,
        @Valid @RequestBody(required = false) ArrivalReview review) {
        return new ApiResponse<>(service.recordArrival(actorContext.required(), checkInId, review), CorrelationIdContext.current());
    }

    public record ImmediateRecoveryRequest(@jakarta.validation.constraints.Size(max = 500) String reason) {}

    @PostMapping("/{checkInId}/immediate-recovery")
    public ApiResponse<CheckInResponse> triggerImmediateHandoverRecovery(
        @PathVariable UUID checkInId,
        @Valid @RequestBody(required = false) ImmediateRecoveryRequest request
    ) {
        return new ApiResponse<>(
            service.triggerImmediateHandoverRecovery(actorContext.required(), checkInId, request == null ? null : request.reason()),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/reject")
    public ApiResponse<CheckInResponse> reject(
        @PathVariable UUID checkInId,
        @Valid @RequestBody RejectCheckInRequest request
    ) {
        return new ApiResponse<>(
            service.reject(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @DeleteMapping("/{checkInId}")
    public ApiResponse<Void> deleteCompletedCheckIn(@PathVariable UUID checkInId) {
        service.archiveCompleted(actorContext.required(), checkInId);
        return new ApiResponse<>(null, CorrelationIdContext.current());
    }
}
