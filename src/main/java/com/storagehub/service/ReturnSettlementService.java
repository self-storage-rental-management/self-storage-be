package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.returns.CustomerConfirmSettlementRequest;
import com.storagehub.api.returns.CustomerCreateReturnRequest;
import com.storagehub.api.returns.CustomerPaySettlementRequest;
import com.storagehub.api.returns.ManagerReviewDisputeRequest;
import com.storagehub.api.returns.ReturnCaseResponse;
import com.storagehub.api.returns.ReturnInspectionRequest;
import com.storagehub.api.returns.StaffCompleteRefundRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.CustomerDecision;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReturnSettlementService {

    private final ReturnCaseRepository returnCaseRepository;
    private final RentalRepository rentalRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReturnCaseResponse createCustomerReturnRequest(
        ActorPrincipal actor,
        UUID rentalId,
        CustomerCreateReturnRequest request
    ) {
        Rental rental = rentalRepository.findByIdAndCustomer_Id(rentalId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found for this customer"));

        if (rental.getStatus() != RentalStatus.active) {
            throw ApiExceptions.conflict("Only active rentals can request return");
        }

        if (request.scheduledDate().isBefore(LocalDate.now())) {
            throw ApiExceptions.validation("scheduledDate cannot be in the past", null);
        }

        boolean hasActiveReturn = returnCaseRepository.existsByRental_IdAndStatusNotIn(
            rentalId,
            List.of(ReturnCaseStatus.completed)
        );
        if (hasActiveReturn) {
            throw ApiExceptions.conflict("A return case is already in progress for this rental");
        }

        User customer = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Customer user not found"));

        // Deposit amount is derived from reservation pricing snapshot security deposit or monthly price
        BigDecimal depositAmount = rental.getMonthlyPrice();
        if (rental.getReservation() != null && rental.getReservation().getSourceQuote() != null) {
            BigDecimal secDeposit = rental.getReservation().getSourceQuote().getSecurityDepositAmount();
            if (secDeposit != null && secDeposit.compareTo(BigDecimal.ZERO) > 0) {
                depositAmount = secDeposit;
            }
        }

        ReturnCase returnCase = new ReturnCase();
        returnCase.setRental(rental);
        returnCase.setFacility(rental.getFacility());
        returnCase.setStorageUnit(rental.getStorageUnit());
        returnCase.setCustomer(customer);
        returnCase.setRequestedBy(customer);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setRequestedAt(Instant.now());
        returnCase.setScheduledDate(request.scheduledDate());
        returnCase.setCustomerNotes(request.notes());
        returnCase.setDepositAmount(depositAmount);

        ReturnCase saved = returnCaseRepository.save(returnCase);

        rental.setStatus(RentalStatus.return_requested);
        rentalRepository.save(rental);

        auditLogService.recordMutation(
            "CUSTOMER_RETURN_REQUESTED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        notificationService.createNotification(
            customer.getId(),
            NotificationType.RETURN,
            "Yêu cầu trả kho đã được ghi nhận",
            "Yêu cầu trả kho cho gian " + rental.getStorageUnit().getCode() + " vào ngày " + request.scheduledDate() + " đã được tiếp nhận.",
            saved.getId()
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ReturnCaseResponse getReturnForCustomer(ActorPrincipal actor, UUID returnId) {
        ReturnCase returnCase = returnCaseRepository.findByIdAndCustomer_Id(returnId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));
        return toResponse(returnCase);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReturnCaseResponse> listReturnsForCustomer(ActorPrincipal actor, int page, int pageSize, String correlationId) {
        Pageable pageable = pageable(page, pageSize);
        Page<ReturnCaseResponse> result = returnCaseRepository.findByCustomer_Id(actor.userId(), pageable)
            .map(this::toResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional
    public ReturnCaseResponse performInspection(
        ActorPrincipal actor,
        UUID returnId,
        ReturnInspectionRequest request
    ) {
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        facilityScopeService.assertCanOperate(actor, returnCase.getFacility().getId());

        if (returnCase.getStatus() != ReturnCaseStatus.requested && returnCase.getStatus() != ReturnCaseStatus.scheduled) {
            throw ApiExceptions.conflict("Return case is not in a state awaiting inspection (current: " + returnCase.getStatus() + ")");
        }

        User staff = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Staff user not found"));

        BigDecimal safeDamage = safeFee(request.damageFee());
        BigDecimal safeCleaning = safeFee(request.cleaningFee());
        BigDecimal safeLostItem = safeFee(request.lostItemFee());
        BigDecimal safeOverdue = safeFee(request.overdueFee());
        BigDecimal safeOutstanding = safeFee(request.outstandingFee());

        BigDecimal totalDeductions = safeDamage
            .add(safeCleaning)
            .add(safeLostItem)
            .add(safeOverdue)
            .add(safeOutstanding)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal deposit = returnCase.getDepositAmount() != null ? returnCase.getDepositAmount() : BigDecimal.ZERO;
        BigDecimal netRefund = deposit.subtract(totalDeductions).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountDue = totalDeductions.subtract(deposit).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        returnCase.setInspectedBy(staff);
        returnCase.setInspectedAt(Instant.now());
        returnCase.setInventoryMatch(request.inventoryMatch());
        returnCase.setDamageClassification(request.damageClassification());
        returnCase.setInspectionNotes(request.staffNotes());
        returnCase.setReturnedKey(request.returnedKey());
        returnCase.setReturnedCard(request.returnedCard());
        returnCase.setReturnedLock(request.returnedLock());
        returnCase.setProposedUnitStatus(request.proposedUnitStatus() != null ? request.proposedUnitStatus() : StorageUnitStatus.available);

        returnCase.setDamageFee(safeDamage);
        returnCase.setCleaningFee(safeCleaning);
        returnCase.setLostItemFee(safeLostItem);
        returnCase.setOverdueFee(safeOverdue);
        returnCase.setOutstandingFee(safeOutstanding);
        returnCase.setTotalDeductions(totalDeductions);
        returnCase.setNetRefundAmount(netRefund);
        returnCase.setAmountDueFromCustomer(amountDue);

        if (request.evidencePhotos() != null && !request.evidencePhotos().isEmpty()) {
            try {
                returnCase.setEvidencePhotosJson(objectMapper.writeValueAsString(request.evidencePhotos()));
            } catch (Exception exception) {
                returnCase.setEvidencePhotosJson("[]");
            }
        }

        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
        ReturnCase saved = returnCaseRepository.save(returnCase);

        Rental rental = saved.getRental();
        rental.setStatus(RentalStatus.return_inspection);
        rentalRepository.save(rental);

        auditLogService.recordMutation(
            "RETURN_INSPECTED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.RETURN,
            "Biên bản nghiệm thu trả kho đã hoàn tất",
            "Nhân viên đã hoàn thành biên bản nghiệm thu gian " + saved.getStorageUnit().getCode() + ". Vui lòng xác nhận quyết toán.",
            saved.getId()
        );

        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse customerConfirmSettlement(
        ActorPrincipal actor,
        UUID returnId,
        CustomerConfirmSettlementRequest request
    ) {
        ReturnCase returnCase = returnCaseRepository.findByIdAndCustomer_Id(returnId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        if (returnCase.getStatus() != ReturnCaseStatus.awaiting_customer_confirmation) {
            throw ApiExceptions.conflict("Return case is not awaiting customer confirmation");
        }

        returnCase.setCustomerConfirmed(true);
        returnCase.setCustomerConfirmedAt(Instant.now());
        returnCase.setCustomerDecision(request.decision());
        returnCase.setCustomerDecisionNote(request.note());

        if (request.decision() == CustomerDecision.disputed) {
            if (request.note() == null || request.note().isBlank()) {
                throw ApiExceptions.validation("Dispute note is required when disputing settlement", null);
            }
            returnCase.setStatus(ReturnCaseStatus.disputed);
        } else {
            // Decision accepted
            if (returnCase.getAmountDueFromCustomer().compareTo(BigDecimal.ZERO) > 0) {
                returnCase.setStatus(ReturnCaseStatus.payment_due);
            } else if (returnCase.getNetRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
                returnCase.setStatus(ReturnCaseStatus.refund_pending);
            } else {
                returnCase.setStatus(ReturnCaseStatus.completed);
                returnCase.setCompletedAt(Instant.now());
                finalizeRentalAndUnit(returnCase);
            }
        }

        ReturnCase saved = returnCaseRepository.save(returnCase);

        auditLogService.recordMutation(
            request.decision() == CustomerDecision.disputed ? "RETURN_SETTLEMENT_DISPUTED" : "RETURN_SETTLEMENT_CONFIRMED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse managerReviewDispute(
        ActorPrincipal actor,
        UUID returnId,
        ManagerReviewDisputeRequest request
    ) {
        authorizationService.require(actor, SystemPermission.MANAGE_RENTALS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        facilityScopeService.assertCanManage(actor, returnCase.getFacility().getId());

        if (returnCase.getStatus() != ReturnCaseStatus.disputed) {
            throw ApiExceptions.conflict("Return case is not in disputed status");
        }

        User manager = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Manager user not found"));

        BigDecimal safeDamage = safeFee(request.damageFee());
        BigDecimal safeCleaning = safeFee(request.cleaningFee());
        BigDecimal safeLostItem = safeFee(request.lostItemFee());
        BigDecimal safeOverdue = safeFee(request.overdueFee());
        BigDecimal safeOutstanding = safeFee(request.outstandingFee());

        BigDecimal totalDeductions = safeDamage
            .add(safeCleaning)
            .add(safeLostItem)
            .add(safeOverdue)
            .add(safeOutstanding)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal deposit = returnCase.getDepositAmount() != null ? returnCase.getDepositAmount() : BigDecimal.ZERO;
        BigDecimal netRefund = deposit.subtract(totalDeductions).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountDue = totalDeductions.subtract(deposit).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        returnCase.setReviewedBy(manager);
        returnCase.setReviewedAt(Instant.now());
        returnCase.setManagerResolutionNote(request.resolutionNote());

        returnCase.setDamageFee(safeDamage);
        returnCase.setCleaningFee(safeCleaning);
        returnCase.setLostItemFee(safeLostItem);
        returnCase.setOverdueFee(safeOverdue);
        returnCase.setOutstandingFee(safeOutstanding);
        returnCase.setTotalDeductions(totalDeductions);
        returnCase.setNetRefundAmount(netRefund);
        returnCase.setAmountDueFromCustomer(amountDue);

        if (request.proposedUnitStatus() != null) {
            returnCase.setProposedUnitStatus(request.proposedUnitStatus());
        }

        if (amountDue.compareTo(BigDecimal.ZERO) > 0) {
            returnCase.setStatus(ReturnCaseStatus.payment_due);
        } else if (netRefund.compareTo(BigDecimal.ZERO) > 0) {
            returnCase.setStatus(ReturnCaseStatus.refund_pending);
        } else {
            returnCase.setStatus(ReturnCaseStatus.completed);
            returnCase.setCompletedAt(Instant.now());
            finalizeRentalAndUnit(returnCase);
        }

        ReturnCase saved = returnCaseRepository.save(returnCase);

        auditLogService.recordMutation(
            "RETURN_DISPUTE_REVIEWED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.RETURN,
            "Kết quả giải quyết khiếu nại quyết toán trả kho",
            "Manager đã xem xét lại quyết toán cho gian " + saved.getStorageUnit().getCode() + ": " + request.resolutionNote(),
            saved.getId()
        );

        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse staffCompleteRefund(
        ActorPrincipal actor,
        UUID returnId,
        StaffCompleteRefundRequest request
    ) {
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        facilityScopeService.assertCanOperate(actor, returnCase.getFacility().getId());

        if (returnCase.getStatus() != ReturnCaseStatus.refund_pending) {
            throw ApiExceptions.conflict("Return case is not pending refund");
        }

        returnCase.setStatus(ReturnCaseStatus.completed);
        returnCase.setCompletedAt(Instant.now());
        returnCase.setSettlementPaidAt(Instant.now());
        returnCase.setSettlementPaymentId(request.transactionReference() != null ? request.transactionReference() : "REFUND-" + UUID.randomUUID().toString().substring(0, 8));

        finalizeRentalAndUnit(returnCase);

        ReturnCase saved = returnCaseRepository.save(returnCase);

        auditLogService.recordMutation(
            "RETURN_REFUND_COMPLETED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.PAYMENT,
            "Hoàn cọc trả kho thành công",
            "Khoản tiền hoàn cọc " + saved.getNetRefundAmount() + " VND đã được chuyển hoàn thành công.",
            saved.getId()
        );

        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse customerPaySettlement(
        ActorPrincipal actor,
        UUID returnId,
        CustomerPaySettlementRequest request
    ) {
        ReturnCase returnCase = returnCaseRepository.findByIdAndCustomer_Id(returnId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        if (returnCase.getStatus() != ReturnCaseStatus.payment_due) {
            throw ApiExceptions.conflict("Return case has no outstanding payment due");
        }

        returnCase.setStatus(ReturnCaseStatus.completed);
        returnCase.setCompletedAt(Instant.now());
        returnCase.setSettlementPaidAt(Instant.now());
        returnCase.setSettlementPaymentId("SETTLEMENT-PAY-" + UUID.randomUUID().toString().substring(0, 8));

        finalizeRentalAndUnit(returnCase);

        ReturnCase saved = returnCaseRepository.save(returnCase);

        auditLogService.recordMutation(
            "RETURN_SETTLEMENT_PAID",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReturnCaseResponse> listReturns(
        ActorPrincipal actor,
        UUID facilityId,
        ReturnCaseStatus status,
        String q,
        int page,
        int pageSize,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_RETURNS);

        Collection<UUID> allowedFacilityIds = null;
        if (facilityScopeService.isFacilityScoped(actor)) {
            allowedFacilityIds = actor.facilityScopes().keySet();
            if (facilityId != null) {
                facilityScopeService.assertCanRead(actor, facilityId);
            }
        }

        Pageable pageable = pageable(page, pageSize);
        Page<ReturnCaseResponse> result = returnCaseRepository.searchReturnCases(
            facilityId,
            status,
            allowedFacilityIds,
            q != null ? q.trim() : null,
            pageable
        ).map(this::toResponse);

        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public ReturnCaseResponse getReturn(ActorPrincipal actor, UUID returnId) {
        authorizationService.require(actor, SystemPermission.VIEW_RETURNS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        if (facilityScopeService.isFacilityScoped(actor)) {
            facilityScopeService.assertCanRead(actor, returnCase.getFacility().getId());
        }

        return toResponse(returnCase);
    }

    private void finalizeRentalAndUnit(ReturnCase returnCase) {
        Rental rental = returnCase.getRental();
        rental.setStatus(RentalStatus.completed);
        rental.setActualReturnedAt(Instant.now());
        rental.setCompletedAt(Instant.now());
        rentalRepository.save(rental);

        StorageUnit unit = returnCase.getStorageUnit();
        if (returnCase.getProposedUnitStatus() == StorageUnitStatus.maintenance) {
            unit.setStatus(StorageUnitStatus.maintenance);
        } else {
            unit.setStatus(StorageUnitStatus.available);
        }
        storageUnitRepository.save(unit);
    }

    private BigDecimal safeFee(BigDecimal fee) {
        if (fee == null || fee.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private PageRequest pageable(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size between 1 and 100", null);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public ReturnCaseResponse toResponse(ReturnCase rc) {
        List<String> evidencePhotos = Collections.emptyList();
        if (rc.getEvidencePhotosJson() != null && !rc.getEvidencePhotosJson().isBlank()) {
            try {
                evidencePhotos = objectMapper.readValue(rc.getEvidencePhotosJson(), new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
            }
        }
        return new ReturnCaseResponse(
            rc.getId(),
            rc.getRental().getId(),
            rc.getFacility().getId(),
            rc.getFacility().getName(),
            rc.getStorageUnit().getId(),
            rc.getStorageUnit().getCode(),
            rc.getCustomer().getId(),
            rc.getCustomer().getFullName(),
            rc.getCustomer().getEmail(),
            rc.getCustomer().getPhone(),
            rc.getStatus(),
            rc.getRequestedAt(),
            rc.getScheduledDate(),
            rc.getCustomerNotes(),
            rc.getInspectedBy() != null ? rc.getInspectedBy().getId() : null,
            rc.getInspectedBy() != null ? rc.getInspectedBy().getFullName() : null,
            rc.getInspectedAt(),
            rc.getInventoryMatch(),
            rc.getDamageClassification(),
            rc.getInspectionNotes(),
            evidencePhotos,
            rc.isReturnedKey(),
            rc.isReturnedCard(),
            rc.isReturnedLock(),
            rc.getProposedUnitStatus(),
            rc.getDepositAmount(),
            rc.getDamageFee(),
            rc.getCleaningFee(),
            rc.getLostItemFee(),
            rc.getOverdueFee(),
            rc.getOutstandingFee(),
            rc.getTotalDeductions(),
            rc.getNetRefundAmount(),
            rc.getAmountDueFromCustomer(),
            rc.getOverdueDays(),
            rc.isCustomerConfirmed(),
            rc.getCustomerConfirmedAt(),
            rc.getCustomerDecision(),
            rc.getCustomerDecisionNote(),
            rc.getReviewedBy() != null ? rc.getReviewedBy().getId() : null,
            rc.getReviewedBy() != null ? rc.getReviewedBy().getFullName() : null,
            rc.getReviewedAt(),
            rc.getManagerResolutionNote(),
            rc.getSettlementPaymentId(),
            rc.getSettlementPaidAt(),
            rc.getCompletedAt()
        );
    }
}
