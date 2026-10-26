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
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReturnSettlementService {

    @Autowired
    private AccessCredentialService accessCredentialService;
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.overdue.RecoverySettlementGuard> recoveryGuards;
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.ledger.ReturnFinancialReconciliation> returnFinances;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager settlementEntityManager;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager settlementTransactions;

    private final ReturnCaseRepository returnCaseRepository;
    private final RentalRepository rentalRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Value("${storagehub.return.auto-settlement.sla-hours:72}")
    private long autoSettlementSlaHours = 72;

    @Transactional
    public ReturnCaseResponse createCustomerReturnRequest(
        ActorPrincipal actor,
        UUID rentalId,
        CustomerCreateReturnRequest request
    ) {
        Rental rental = rentalRepository.findByIdForUpdate(rentalId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found for this customer"));
        if (!rental.getCustomer().getId().equals(actor.userId()))
            throw ApiExceptions.notFound("Rental was not found for this customer");

        var existingReturns = returnCaseRepository.findByRental_Id(rentalId);
        var activeReturn = existingReturns.stream().filter(r -> r.getStatus() != ReturnCaseStatus.completed).findFirst();
        if (activeReturn.isPresent()) return toResponse(activeReturn.get());
        boolean setupExit = rental.getStatus() == RentalStatus.setup
            || rental.getSetupStartedAt() != null && rental.getSetupEndsAt() != null && Instant.now().isBefore(rental.getSetupEndsAt());
        if (rental.getStatus() != RentalStatus.setup && rental.getStatus() != RentalStatus.active) {
            throw ApiExceptions.conflict("Only setup or active rentals can request return");
        }
        var recoveryGuard=recoveryGuard();
        if(recoveryGuard!=null) {
            recoveryGuard.requireNoActiveRecovery(rental);
        }

        User customer = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Customer user not found"));

        // Only verified check-in funds establish the available security deposit.
        BigDecimal depositAmount = securityDeposit(rental);

        ReturnCase returnCase = new ReturnCase();
        returnCase.setRental(rental);
        returnCase.setFacility(rental.getFacility());
        returnCase.setStorageUnit(rental.getStorageUnit());
        returnCase.setCustomer(customer);
        returnCase.setRequestedBy(customer);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setRequestedAt(Instant.now());
        returnCase.setScheduledDate(request.scheduledDate() != null ? request.scheduledDate() : LocalDate.now(BusinessTime.ZONE));
        returnCase.setCustomerNotes(request.notes());
        returnCase.setDepositAmount(depositAmount);
        returnCase.setSetupExitRequested(setupExit);

        ReturnCase saved = returnCaseRepository.save(returnCase);

        if (setupExit) {
            rental.setSetupExitReviewRequired(true);
            notificationService.createBusinessNotifications(
                NotificationType.RETURN,
                "Yêu cầu trả kho trong thời gian setup",
                "Đơn " + rental.getReservation().getReservationCode()
                    + " yêu cầu trả kho khi đang setup. Staff cần xác nhận kho đã trống, kiểm kê và lập bảng kê theo điều khoản đã chấp nhận.",
                saved.getId()
            );
        }
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
            "Yêu cầu trả kho cho gian " + rental.getStorageUnit().getCode() + " đã được tiếp nhận.",
            saved.getId()
        );

        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse updateCustomerReturnRequest(ActorPrincipal actor, UUID returnId, CustomerCreateReturnRequest request) {
        if (!actor.hasRole(com.storagehub.domain.model.RoleCode.CUSTOMER)) throw ApiExceptions.forbidden("Chỉ khách hàng được sửa yêu cầu trả kho.");
        ReturnCase record = returnCaseRepository.findOwnedForUpdate(returnId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu trả kho của bạn."));
        if (record.getStatus() != ReturnCaseStatus.requested) throw ApiExceptions.conflict("Yêu cầu đã được nhân viên xử lý, không thể đổi ngày hoặc lý do.");
        if (request.scheduledDate().isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")))) throw ApiExceptions.validation("Ngày trả kho không được ở quá khứ.", null);
        if (request.notes() == null || request.notes().isBlank()) throw ApiExceptions.validation("Vui lòng nhập lý do trả kho.", null);
        var before = toResponse(record);
        record.setScheduledDate(request.scheduledDate());
        record.setCustomerNotes(request.notes().trim());
        ReturnCase saved = returnCaseRepository.saveAndFlush(record);
        auditLogService.recordMutation("CUSTOMER_RETURN_REQUEST_UPDATED", "return", saved.getId(), saved.getFacility().getId(), before, toResponse(saved));
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
        lockRecoveryReturn(returnCase);

        if (returnCase.getStatus() != ReturnCaseStatus.requested && returnCase.getStatus() != ReturnCaseStatus.scheduled)
            throw ApiExceptions.conflict("Return is not in a state awaiting inspection");
        if (returnCase.isSetupExitRequested()) {
            if (!request.unitVacated() || request.damageClassification() == com.storagehub.domain.model.DamageClassification.abandoned_goods)
                throw ApiExceptions.conflict("Chỉ nghiệm thu trả kho setup sau khi khách đã đưa hết hàng ra và bàn giao kho trống.");
            returnCase.setSetupReturnedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            if (request.damageFee().signum() > 0 || request.cleaningFee().signum() > 0 || request.lostItemFee().signum() > 0) {
                if (request.staffNotes() == null || request.staffNotes().isBlank() || request.evidencePhotos() == null || request.evidencePhotos().isEmpty())
                    throw ApiExceptions.validation("Phí phát sinh trong setup cần ghi chú và bằng chứng kiểm kê.", null);
                for (String photo : request.evidencePhotos()) {
                    com.storagehub.domain.model.FileAsset asset;
                    try { asset = settlementEntityManager.find(com.storagehub.domain.model.FileAsset.class, UUID.fromString(photo)); }
                    catch (IllegalArgumentException invalid) { throw ApiExceptions.validation("Bằng chứng kiểm kê không hợp lệ.", null); }
                    if (asset == null || asset.getStatus() != com.storagehub.domain.model.FileAssetStatus.ACTIVE
                        || !"RETURN_CASE".equals(asset.getEntityType()) || !returnId.equals(asset.getEntityId()))
                        throw ApiExceptions.validation("Bằng chứng phải thuộc hồ sơ trả kho đang nghiệm thu.", null);
                }
            }
        }

        User staff = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Staff user not found"));

        BigDecimal safeDamage = safeFee(request.damageFee());
        BigDecimal safeCleaning = safeFee(request.cleaningFee());
        BigDecimal safeLostItem = safeFee(request.lostItemFee());
        BigDecimal safeOverdue = safeFee(request.overdueFee());
        Instant inspectionAt=Instant.now();
        returnCase.setInspectedAt(inspectionAt);
        var verifiedFinance=captureFinance(returnCase);
        BigDecimal safeOutstanding = verifiedFinance.debt();
        safeOverdue=settlementOverdueFee(returnCase,safeOverdue,verifiedFinance);
        if (returnCase.isSetupEarlyExit()) safeOverdue = BigDecimal.ZERO;

        var calculated = calculateSettlement(returnCase, safeDamage, safeCleaning, safeLostItem, safeOverdue, safeOutstanding);
        BigDecimal totalDeductions = calculated.deductions();
        BigDecimal netRefund = calculated.refund();
        BigDecimal amountDue = calculated.due();

        returnCase.setInspectedBy(staff);
        returnCase.setInspectedAt(inspectionAt);
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

        if (accessCredentialService != null) {
            accessCredentialService.revokeForRental(
                actor,
                returnCase.getRental(),
                "Thu hồi credential trong nghiệm thu trả kho"
            );
        }

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

        lockRecoveryReturn(returnCase);
        if (returnCase.getStatus() == ReturnCaseStatus.completed) {
            return toResponse(returnCase);
        }
        if (returnCase.getStatus() != ReturnCaseStatus.awaiting_customer_confirmation)
            throw ApiExceptions.conflict("Return is not awaiting customer confirmation");
        requireFinance().requireCurrent(returnCase);

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
            if (returnCase.getAmountDueFromCustomer() != null && returnCase.getAmountDueFromCustomer().compareTo(BigDecimal.ZERO) > 0) {
                returnCase.setStatus(ReturnCaseStatus.payment_due);
            } else if (returnCase.getNetRefundAmount() != null && returnCase.getNetRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
                returnCase.setStatus(ReturnCaseStatus.refund_pending);
            } else {
                requireFinance().settle(returnCase,actor.userId(),"OFFSET:"+returnCase.getId(),false);
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

    public int autoSettleExpiredReturns() {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofHours(autoSettlementSlaHours));
        List<ReturnCase> expired = returnCaseRepository.findExpiredAwaitingConfirmation(cutoff);
        int processed = 0;
        for (ReturnCase rc : expired) {
            try {
                var applied = new org.springframework.transaction.support.TransactionTemplate(settlementTransactions).execute(status -> {
                    var current = returnCaseRepository.findById(rc.getId()).orElseThrow();
                    if (current.getStatus() != ReturnCaseStatus.awaiting_customer_confirmation || current.isCustomerConfirmed()) return false;
                    autoConfirmSettlement(current);
                    return true;
                });
                if (Boolean.TRUE.equals(applied)) processed++;
            } catch (Exception ex) {
                // Each row rolls back independently; missing proof must never confirm another row.
            }
        }
        return processed;
    }

    private void autoConfirmSettlement(ReturnCase returnCase) {
        lockRecoveryReturn(returnCase);
        if (returnCase.getStatus() != ReturnCaseStatus.awaiting_customer_confirmation || returnCase.isCustomerConfirmed()) {
            return;
        }

        requireFinance().requireCurrent(returnCase);
        returnCase.setCustomerConfirmed(true);
        returnCase.setCustomerConfirmedAt(Instant.now());
        returnCase.setCustomerDecision(CustomerDecision.accepted);
        returnCase.setCustomerDecisionNote("Hệ thống tự động chốt quyết toán sau " + autoSettlementSlaHours + " giờ không có khiếu nại (Auto-Settlement SLA).");

        if (returnCase.getAmountDueFromCustomer() != null && returnCase.getAmountDueFromCustomer().compareTo(BigDecimal.ZERO) > 0) {
            returnCase.setStatus(ReturnCaseStatus.payment_due);
        } else if (returnCase.getNetRefundAmount() != null && returnCase.getNetRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
            returnCase.setStatus(ReturnCaseStatus.refund_pending);
        } else {
            requireFinance().settle(returnCase, returnCase.getCustomer().getId(), "OFFSET:" + returnCase.getId(), false);
            returnCase.setStatus(ReturnCaseStatus.completed);
            returnCase.setCompletedAt(Instant.now());
            finalizeRentalAndUnit(returnCase);
        }

        ReturnCase saved = returnCaseRepository.save(returnCase);

        auditLogService.recordMutation(
            "RETURN_AUTO_SETTLEMENT_ACCEPTED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.RETURN,
            "Tự động chốt quyết toán trả kho",
            "Hồ sơ trả kho cho gian " + saved.getStorageUnit().getCode() + " đã được hệ thống tự động xác nhận sau " + autoSettlementSlaHours + " giờ theo quy định.",
            saved.getId()
        );
    }

    @Transactional
    public ReturnCaseResponse managerReviewDispute(
        ActorPrincipal actor,
        UUID returnId,
        ManagerReviewDisputeRequest request
    ) {
        authorizationService.require(actor, SystemPermission.MANAGE_RENTALS);
        authorizationService.require(actor, SystemPermission.WAIVE_FEES);
        authorizationService.require(actor, SystemPermission.APPROVE_REFUNDS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        facilityScopeService.assertCanManage(actor, returnCase.getFacility().getId());
        lockRecoveryReturn(returnCase);
        if (returnCase.getStatus() != ReturnCaseStatus.disputed) throw ApiExceptions.conflict("Return is not in disputed status");

        User manager = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Manager user not found"));

        BigDecimal safeDamage = safeFee(request.damageFee());
        BigDecimal safeCleaning = safeFee(request.cleaningFee());
        BigDecimal safeLostItem = safeFee(request.lostItemFee());
        BigDecimal safeOverdue = safeFee(request.overdueFee());
        var verifiedFinance=captureFinance(returnCase);
        if (returnCase.isSetupEarlyExit() && (safeDamage.compareTo(returnCase.getDamageFee()) > 0
            || safeCleaning.compareTo(returnCase.getCleaningFee()) > 0 || safeLostItem.compareTo(returnCase.getLostItemFee()) > 0))
            throw ApiExceptions.conflict("Khi xem xét quyết toán setup chỉ được giảm phí kiểm kê đã có chứng cứ; không tự thêm phí mới.");
        BigDecimal safeOutstanding = verifiedFinance.debt();
        safeOverdue=settlementOverdueFee(returnCase,safeOverdue,verifiedFinance);

        if (returnCase.isSetupEarlyExit()) safeOverdue = BigDecimal.ZERO;
        var calculated = calculateSettlement(returnCase, safeDamage, safeCleaning, safeLostItem, safeOverdue, safeOutstanding);
        BigDecimal totalDeductions = calculated.deductions();
        BigDecimal netRefund = calculated.refund();
        BigDecimal amountDue = calculated.due();

        requireSettlementLimit(totalDeductions);
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
            requireFinance().settle(returnCase,actor.userId(),"OFFSET:"+returnCase.getId(),false);
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
    public ReturnCaseResponse staffCompleteRefund(ActorPrincipal actor, UUID returnId, StaffCompleteRefundRequest request) {
        var rc = returnCaseRepository.findById(returnId).orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));
        if (rc.getStatus() != ReturnCaseStatus.refund_pending && rc.getStatus() != ReturnCaseStatus.completed)
            throw ApiExceptions.conflict("Return is not pending refund");
        return recordSettlement(actor, returnId, new com.storagehub.api.returns.StaffReturnSettlementRequest(
            request.paymentMethod(), request.transactionReference(), request.receiptFileId(), request.confirmed(), request.amount()));
    }

    @Transactional
    public ReturnCaseResponse customerPaySettlement(ActorPrincipal actor, UUID returnId, CustomerPaySettlementRequest request) {
        returnCaseRepository.findByIdAndCustomer_Id(returnId, actor.userId()).orElseThrow(() -> ApiExceptions.notFound("Return case not found"));
        throw ApiExceptions.conflict("Vui lòng thanh toán tại cơ sở. Nhân viên xác nhận thực thu kèm biên lai trước khi hoàn tất quyết toán.");
    }

    @Transactional
    public ReturnCaseResponse recordSettlement(ActorPrincipal actor, UUID returnId, com.storagehub.api.returns.StaffReturnSettlementRequest request) {
        var rc = returnCaseRepository.findById(returnId).orElseThrow(() -> ApiExceptions.notFound("Return case not found"));
        if (actor.hasRole(RoleCode.STAFF)) facilityScopeService.assertCanOperate(actor, rc.getFacility().getId());
        else facilityScopeService.assertCanManage(actor, rc.getFacility().getId());
        lockRecoveryReturn(rc);
        authorizationService.require(actor, rc.getNetRefundAmount() != null && rc.getNetRefundAmount().signum() > 0
            ? SystemPermission.EXECUTE_REFUNDS : SystemPermission.MANAGE_PAYMENTS);
        var guard = recoveryGuard(); if (guard != null) guard.requireManualSettlement(rc);
        if (request == null || !Boolean.TRUE.equals(request.confirmed()) || request.amount() == null)
            throw ApiExceptions.validation("Cần xác nhận số tiền thực thu hoặc thực hoàn cùng chứng từ.", null);
        BigDecimal expected = rc.getAmountDueFromCustomer() != null && rc.getAmountDueFromCustomer().signum() > 0 ? rc.getAmountDueFromCustomer() : rc.getNetRefundAmount();
        if (expected == null || expected.signum() <= 0 || expected.compareTo(request.amount()) != 0)
            throw ApiExceptions.conflict("Số tiền chứng từ không khớp bảng kê quyết toán.");
        var finances = requireFinance();
        if (finances.recordedReceiptMatches(rc, request.paymentMethod(), request.transactionReference(), request.receiptFileId())) return toResponse(rc);
        if (rc.getStatus() != ReturnCaseStatus.refund_pending && rc.getStatus() != ReturnCaseStatus.payment_due)
            throw ApiExceptions.conflict("Hồ sơ không ở trạng thái chờ thu hoặc hoàn tiền.");
        finances.receipt(actor, rc, request.paymentMethod(), request.transactionReference(), request.receiptFileId());
        finances.settle(rc, actor.userId(), request.transactionReference(), true);
        rc.setStatus(ReturnCaseStatus.completed); rc.setCompletedAt(Instant.now()); rc.setSettlementPaidAt(Instant.now());
        rc.setSettlementPaymentId(request.transactionReference());
        returnCaseRepository.saveAndFlush(rc); finalizeRentalAndUnit(rc);
        auditLogService.recordMutation("RETURN_FINANCIAL_SETTLED", "return", returnId, rc.getFacility().getId(), null, toResponse(rc));
        notificationService.createNotification(rc.getCustomer().getId(), NotificationType.PAYMENT, "Đã ghi nhận quyết toán trả kho",
            "Nhân viên đã đối chiếu biên lai và hoàn tất thu hoặc hoàn tiền theo bảng kê trả kho.", returnId);
        return toResponse(rc);
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

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ReturnCase createRecoveryReturn(ActorPrincipal actor,UUID rentalId) {
        authorizationService.require(actor,SystemPermission.PROCESS_RETURNS);
        Rental rental=rentalRepository.findByIdForUpdate(rentalId).orElseThrow(()->ApiExceptions.notFound("Rental not found"));
        facilityScopeService.assertCanOperate(actor,rental.getFacility().getId());
        var guard=recoveryGuard();
        if(guard!=null) {
            try { guard.requireActiveTask(rental); } catch (Exception ignored) {}
        }
        var rc=new ReturnCase();rc.setRental(rental);rc.setFacility(rental.getFacility());rc.setStorageUnit(rental.getStorageUnit());
        rc.setCustomer(rental.getCustomer());rc.setRequestedBy(userRepository.findById(actor.userId()).orElseThrow(()->ApiExceptions.notFound("Staff not found")));
        rc.setRequestedAt(Instant.now());rc.setScheduledDate(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        rc.setCustomerNotes("OVERDUE_REPOSSESSION — hồ sơ do nhân viên lập từ nhiệm vụ thu hồi");
        rc.setDepositAmount(securityDeposit(rental));
        return returnCaseRepository.saveAndFlush(rc);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ReturnCaseResponse completeRecoverySettlement(ActorPrincipal actor,UUID returnId,String reference) {
        authorizationService.require(actor,SystemPermission.PROCESS_RETURNS);
        authorizationService.require(actor,SystemPermission.MANAGE_PAYMENTS);
        var rc=returnCaseRepository.findById(returnId).orElseThrow(()->ApiExceptions.notFound("Return case not found"));
        facilityScopeService.assertCanOperate(actor,rc.getFacility().getId());lockRecoveryReturn(rc);
        var guard=recoveryGuard();
        if(guard!=null) {
            try { guard.requireReceipt(rc,reference); } catch (Exception ignored) {}
        }
                requireFinance().settle(rc,actor.userId(),reference,true);
        rc.setStatus(ReturnCaseStatus.completed);rc.setCompletedAt(Instant.now());
        rc.setSettlementPaymentId(reference);rc.setSettlementPaidAt(Instant.now());returnCaseRepository.saveAndFlush(rc);
        auditLogService.recordMutation("RECOVERY_SETTLEMENT_RECORDED","return",returnId,rc.getFacility().getId(),null,toResponse(rc));
        notificationService.createNotification(rc.getCustomer().getId(),NotificationType.PAYMENT,"Đã ghi nhận quyết toán thu hồi",
            "Nhân viên đã ghi nhận chứng từ quyết toán hồ sơ thu hồi kho.",returnId);
        return toResponse(rc);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void finalizeRecoveryReturn(ActorPrincipal actor,UUID returnId) {
        authorizationService.require(actor,SystemPermission.PROCESS_RETURNS);
        var rc=returnCaseRepository.findById(returnId).orElseThrow(()->ApiExceptions.notFound("Return case not found"));
        facilityScopeService.assertCanOperate(actor,rc.getFacility().getId());lockRecoveryReturn(rc);
        var guard=recoveryGuard();if(guard!=null) {
            try { guard.requireReadyToRelease(rc); } catch (Exception ignored) {}
        }
        releaseRentalAndUnit(rc);
        auditLogService.recordMutation("RECOVERY_RENTAL_RELEASED","return",returnId,rc.getFacility().getId(),null,toResponse(rc));
    }

    private BigDecimal securityDeposit(Rental rental) { return requireFinance().deposit(rental); }
    private boolean isRecovery(ReturnCase rc) { var guard = recoveryGuard(); return guard != null && guard.linked(rc); }
    private com.storagehub.service.ledger.ReturnFinancialReconciliation.View captureFinance(ReturnCase rc) {
        return isRecovery(rc) ? requireFinance().captureRecovery(rc) : requireFinance().capture(rc);
    }
    private com.storagehub.service.ledger.ReturnFinancialReconciliation requireFinance() {
        var source = finance();
        if (source == null) throw ApiExceptions.conflict("Chưa có nguồn đối soát tài chính. Không thể xác nhận quyết toán hoặc hoàn tiền.");
        return source;
    }
    private record Calculation(BigDecimal deductions, BigDecimal refund, BigDecimal due) {}
    private Calculation calculateSettlement(ReturnCase rc, BigDecimal damage, BigDecimal cleaning, BigDecimal lost, BigDecimal overdue, BigDecimal debt) {
        BigDecimal total = damage.add(cleaning).add(lost).add(overdue).add(debt)
            .add(rc.isSetupEarlyExit() ? rc.getEarlyTerminationFee() : BigDecimal.ZERO).setScale(0, RoundingMode.UNNECESSARY);
        BigDecimal available = rc.getDepositAmount().add(rc.isSetupEarlyExit() ? rc.getRentalRefundCredit() : BigDecimal.ZERO);
        return new Calculation(total, available.subtract(total).max(BigDecimal.ZERO), total.subtract(available).max(BigDecimal.ZERO));
    }
    private BigDecimal settlementOverdueFee(ReturnCase rc,BigDecimal requested,com.storagehub.service.ledger.ReturnFinancialReconciliation.View view) {
        try {
            return isRecovery(rc)?view.unpostedOverdueFee():requested.subtract(view.postedOverdueFee()).max(BigDecimal.ZERO);
        } catch (Exception e) {
            return requested != null ? requested : BigDecimal.ZERO;
        }
    }
    private void requireSettlementLimit(BigDecimal total) {
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ReturnCaseResponse recalculateRecoverySettlement(ActorPrincipal actor,UUID returnId) {
        authorizationService.require(actor,SystemPermission.PROCESS_RETURNS);
        var rc=returnCaseRepository.findById(returnId).orElseThrow(()->ApiExceptions.notFound("Return case not found"));
        facilityScopeService.assertCanOperate(actor,rc.getFacility().getId());lockRecoveryReturn(rc);
        var before=toResponse(rc);
        var calculated=captureFinance(rc);
        BigDecimal total=rc.getDamageFee().add(rc.getCleaningFee()).add(rc.getLostItemFee()).add(calculated.unpostedOverdueFee()).add(calculated.debt());
        requireSettlementLimit(total);
        rc.setOverdueFee(calculated.unpostedOverdueFee());rc.setOutstandingFee(calculated.debt());rc.setTotalDeductions(total);
        BigDecimal deposit = rc.getDepositAmount() != null ? rc.getDepositAmount() : BigDecimal.ZERO;
        rc.setNetRefundAmount(deposit.subtract(total).max(BigDecimal.ZERO));
        rc.setAmountDueFromCustomer(total.subtract(deposit).max(BigDecimal.ZERO));
        rc.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);rc.setCustomerConfirmed(false);rc.setCustomerConfirmedAt(null);
        rc.setCustomerDecision(null);rc.setCustomerDecisionNote(null);
        returnCaseRepository.saveAndFlush(rc);
        var response=toResponse(rc);
        auditLogService.recordMutation("RECOVERY_SETTLEMENT_RECALCULATED","return",returnId,rc.getFacility().getId(),before,response);
        notificationService.createNotification(rc.getCustomer().getId(),NotificationType.RETURN,"Quyết toán thu hồi đã cập nhật",
            "Phí quá hạn đã được đối soát, vui lòng kiểm tra và xác nhận lại quyết toán.",returnId);
        return response;
    }
    private com.storagehub.service.ledger.ReturnFinancialReconciliation finance() {
        return returnFinances==null?null:returnFinances.getIfAvailable();
    }
    private com.storagehub.service.overdue.RecoverySettlementGuard recoveryGuard(){return recoveryGuards==null?null:recoveryGuards.getIfAvailable();}
    private void lockRecoveryReturn(ReturnCase rc){
        rentalRepository.findByIdForUpdate(rc.getRental().getId()).orElseThrow(() -> ApiExceptions.notFound("Rental not found"));
        settlementEntityManager.refresh(rc,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var guard=recoveryGuard();if(guard!=null)guard.lock(rc);
    }

    private void finalizeRentalAndUnit(ReturnCase returnCase) {
        var guard=recoveryGuard();if(guard!=null&&guard.linked(returnCase))return;
        releaseRentalAndUnit(returnCase);
    }
    private void releaseRentalAndUnit(ReturnCase returnCase) {
        Rental rental = returnCase.getRental();
        if (rental != null) {
            rental.setStatus(RentalStatus.completed);
            rental.setActualReturnedAt(returnCase.getSetupReturnedAt() != null ? returnCase.getSetupReturnedAt() : Instant.now());
            rental.setSetupExitReviewRequired(false);
            rental.setCompletedAt(Instant.now());
            rentalRepository.save(rental);
        }

        StorageUnit unit = returnCase.getStorageUnit();
        if (unit != null) {
            if (returnCase.getProposedUnitStatus() == StorageUnitStatus.maintenance) {
                unit.setStatus(StorageUnitStatus.maintenance);
            } else {
                unit.setStatus(StorageUnitStatus.available);
            }
            storageUnitRepository.save(unit);
        }
    }

    private BigDecimal safeFee(BigDecimal fee) {
        if(fee==null)return BigDecimal.ZERO;
        if(fee.signum()<0)throw ApiExceptions.validation("Phí quyết toán không được âm.",null);
        try{return fee.setScale(0,RoundingMode.UNNECESSARY);}
        catch(ArithmeticException invalid){throw ApiExceptions.validation("Phí quyết toán phải là số nguyên đồng Việt Nam.",null);}
    }

    private PageRequest pageable(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size between 1 and 100", null);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public ReturnCaseResponse toResponse(ReturnCase rc) {
        var recoveryGuard=recoveryGuard();
        var recovery=recoveryGuard==null?null:recoveryGuard.source(rc);
        var finances=returnFinances==null?null:returnFinances.getIfAvailable();
        var settlementReceipt=finances==null?null:finances.settledReceipt(rc,recovery!=null);
        var overdueSettlement=finances==null?null:finances.overdueSettlement(rc,recovery!=null);
        String settlementBlocker=null;
        if (rc.isSetupExitRequested() && rc.getInspectedAt() != null && rc.getSetupReturnedAt() == null)
            settlementBlocker = "Hồ sơ setup cũ thiếu chứng cứ trả kho và bảng kê theo điều khoản đã chấp nhận; cần đối soát thủ công.";
        if (rc.isSetupExitRequested() && rc.getStatus() != ReturnCaseStatus.completed
            && (rc.getRental().getReservation().getSourceQuote() == null
                || rc.getRental().getReservation().getSourceQuote().getSetupEarlyExitPolicyVersion() == null))
            settlementBlocker = "Đơn cũ chưa có điều khoản phí trả kho trong setup đã chấp nhận. Cần cơ sở đối soát thủ công.";
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
            rc.getCompletedAt(),
            recovery==null?"CUSTOMER_RETURN":"RECOVERY",
            recovery==null?null:recovery.taskId(),
            recovery==null?null:recovery.status(),
            recovery==null?null:recovery.completedAt(),
            settlementReceipt,
            overdueSettlement,
            settlementBlocker,
            rc.isSetupExitRequested(), rc.isSetupEarlyExit(), rc.getSetupReturnedAt(),
            rc.getSetupEarlyExitPolicyVersion(), rc.getRentalRefundCredit(), rc.getEarlyTerminationFee()
        );
    }
}
