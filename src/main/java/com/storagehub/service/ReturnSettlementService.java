package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.returns.CustomerConfirmSettlementRequest;
import com.storagehub.api.returns.CustomerCreateReturnRequest;
import com.storagehub.api.returns.CustomerPaySettlementRequest;
import com.storagehub.api.returns.BusinessSetupExitDecisionRequest;
import com.storagehub.api.returns.BusinessLegacyPricingDecisionRequest;
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
import com.storagehub.domain.model.ReturnType;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SettlementMode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.FileAssetStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
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
    @Autowired
    private ReservationPricingSnapshotRepository reservationPricingSnapshotRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private FileAssetRepository fileAssetRepository;
    @Autowired(required = false)
    private VnpayService vnpayService;
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
        LocalDate scheduledDate = returnCase.getScheduledDate();
        boolean normalReturn = !setupExit
            && rental.getContractEndDate() != null
            && !scheduledDate.isBefore(rental.getContractEndDate());
        returnCase.setReturnType(setupExit
            ? ReturnType.RETURN_IN_SETUP
            : normalReturn ? ReturnType.NORMAL : ReturnType.EARLY_RETURN);
        returnCase.setActiveReturnRepricingRequired(!setupExit && !normalReturn);
        returnCase.setSettlementMode(setupExit || !normalReturn && !hasPricingTiers(rental)
            ? SettlementMode.MANUAL_REVIEW : SettlementMode.AUTO);
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
    public ReturnCaseResponse businessReviewSetupExit(
        ActorPrincipal actor,
        UUID returnId,
        BusinessSetupExitDecisionRequest request
    ) {
        if (actor == null || (!actor.hasRole(RoleCode.BUSINESS) && !actor.hasRole(RoleCode.ADMIN))) {
            throw ApiExceptions.forbidden("Business Operations role is required");
        }
        authorizationService.require(actor, SystemPermission.APPROVE_REFUNDS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));
        facilityScopeService.assertCanManage(actor, returnCase.getFacility().getId());
        lockRecoveryReturn(returnCase);

        Rental rental = returnCase.getRental();
        if (rental == null || !rental.isSetupExitReviewRequired()) {
            throw ApiExceptions.conflict("This return case is not awaiting a Business setup-exit decision");
        }
        if (rental.getMoveInCompletedAt() != null && returnCase.getInspectedAt() == null) {
            throw ApiExceptions.conflict("Staff must inspect goods already moved into the unit before Business decides the refund");
        }

        var setupPricing = setupExitCalculation(returnCase);
        BigDecimal maximumRentalRefund = setupPricing == null
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : setupPricing.rentalRefundAmount();
        BigDecimal approvedRentalRefund = request.approvedRentalRefundAmount() == null
            ? maximumRentalRefund
            : request.approvedRentalRefundAmount().setScale(2, RoundingMode.HALF_UP);
        if (approvedRentalRefund.compareTo(maximumRentalRefund) > 0) {
            throw ApiExceptions.conflict("Approved rental refund cannot exceed the snapshot calculation");
        }

        BigDecimal deposit = returnCase.getDepositAmount() == null
            ? BigDecimal.ZERO : returnCase.getDepositAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal approvedRefund = request.approvedRefundAmount().setScale(2, RoundingMode.HALF_UP);
        if (approvedRefund.compareTo(deposit) > 0) {
            throw ApiExceptions.conflict("Approved setup-exit refund cannot exceed the verified security deposit");
        }

        User business = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Business user not found"));
        BigDecimal retained = deposit.subtract(approvedRefund).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rentalCharge = setupPricing == null
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : setupPricing.totalRentalCharge().add(maximumRentalRefund).subtract(approvedRentalRefund)
                .setScale(2, RoundingMode.HALF_UP);
        returnCase.setReviewedBy(business);
        returnCase.setReviewedAt(Instant.now());
        returnCase.setManagerResolutionNote("Business setup-exit decision: " + request.resolutionNote().trim());
        returnCase.setEarlyTerminationCharge(rentalCharge);
        returnCase.setRentalRefundAmount(approvedRentalRefund);
        returnCase.setTotalDeductions(retained.add(rentalCharge).setScale(2, RoundingMode.HALF_UP));
        returnCase.setNetRefundAmount(approvedRefund.add(approvedRentalRefund).setScale(2, RoundingMode.HALF_UP));
        returnCase.setAmountDueFromCustomer(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        boolean refundPending = approvedRefund.signum() > 0 || approvedRentalRefund.signum() > 0;
        returnCase.setStatus(refundPending ? ReturnCaseStatus.refund_pending : ReturnCaseStatus.completed);

        rental.setSetupExitReviewRequired(false);
        if (!refundPending) {
            var reconciliation = finance();
            if (reconciliation != null) {
                reconciliation.requireCurrent(returnCase);
                reconciliation.settle(returnCase, actor.userId(), "SETUP_EXIT_OFFSET:" + returnCase.getId(), false);
            }
            returnCase.setCompletedAt(Instant.now());
            finalizeRentalAndUnit(returnCase);
        } else {
            rentalRepository.save(rental);
        }

        ReturnCase saved = returnCaseRepository.save(returnCase);
        auditLogService.recordMutation(
            "SETUP_EXIT_REFUND_DECIDED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );
        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.RETURN,
            "Business đã quyết định xử lý yêu cầu trả kho trong setup",
            refundPending
                ? "Khoản hoàn tiền thuê và/hoặc cọc đã được duyệt. Staff sẽ đối chiếu chứng từ thực tế trước khi hoàn tiền."
                : "Yêu cầu đã được chốt không hoàn tiền và hệ thống đã giải phóng hồ sơ trả kho.",
            saved.getId()
        );
        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse businessReviewLegacyPricing(
        ActorPrincipal actor,
        UUID returnId,
        BusinessLegacyPricingDecisionRequest request
    ) {
        authorizationService.require(actor, SystemPermission.APPROVE_REFUNDS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));
        facilityScopeService.assertCanManage(actor, returnCase.getFacility().getId());
        if (returnCase.getStatus() != ReturnCaseStatus.awaiting_business_review
            || !returnCase.isLegacyPricingReviewRequired()) {
            throw ApiExceptions.conflict("This return case is not awaiting legacy pricing review");
        }
        ReservationPricingSnapshot snapshot = reservationPricingSnapshot(returnCase);
        if (snapshot == null || snapshot.getNetRentalAmount() == null) {
            throw ApiExceptions.conflict("Reservation pricing snapshot is missing");
        }
        if (request.pricingSnapshotJson() == null || request.pricingSnapshotJson().isBlank()
            || "[]".equals(request.pricingSnapshotJson().trim())) {
            throw ApiExceptions.validation("A completed legacy pricing snapshot is required", null);
        }
        try {
            var legacySnapshot = objectMapper.readTree(request.pricingSnapshotJson());
            if (legacySnapshot == null || legacySnapshot.isNull()
                || (!legacySnapshot.isArray() && !legacySnapshot.isObject())) {
                throw new IllegalArgumentException("Legacy pricing snapshot must be a JSON object or array");
            }
        } catch (Exception exception) {
            throw ApiExceptions.validation("Legacy pricing snapshot is invalid JSON", null);
        }
        var evidence = fileAssetRepository.findById(request.sourceDocumentFileId())
            .orElseThrow(() -> ApiExceptions.notFound("The original contract or quote evidence was not found"));
        if (evidence.getStatus() != FileAssetStatus.ACTIVE) {
            throw ApiExceptions.conflict("The original contract or quote evidence is not active");
        }
        BigDecimal charge = request.approvedRentalCharge().setScale(2, RoundingMode.HALF_UP);
        BigDecimal refund = request.approvedRentalRefund().setScale(2, RoundingMode.HALF_UP);
        if (charge.add(refund).compareTo(snapshot.getNetRentalAmount()) > 0) {
            throw ApiExceptions.conflict("Legacy pricing decision cannot exceed the reservation rental amount");
        }
        BigDecimal securityDeductions = zeroIfNull(returnCase.getDamageFee())
            .add(zeroIfNull(returnCase.getCleaningFee()))
            .add(zeroIfNull(returnCase.getLostItemFee()))
            .add(zeroIfNull(returnCase.getOverdueFee()))
            .add(zeroIfNull(returnCase.getOutstandingFee()))
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = zeroIfNull(returnCase.getDepositAmount());
        BigDecimal securityRefund = deposit.subtract(securityDeductions).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountDue = securityDeductions.subtract(deposit).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        User business = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Business user not found"));
        returnCase.setReviewedBy(business);
        returnCase.setReviewedAt(Instant.now());
        returnCase.setManagerResolutionNote("Legacy pricing decision: " + request.resolutionNote().trim());
        returnCase.setLegacyPricingSnapshotJson(request.pricingSnapshotJson().trim());
        returnCase.setLegacyPricingEvidenceFileId(evidence.getId());
        returnCase.setLegacyPricingDecisionReason(request.resolutionNote().trim());
        returnCase.setEarlyTerminationCharge(charge);
        returnCase.setRentalRefundAmount(refund);
        returnCase.setTotalDeductions(securityDeductions.add(charge).setScale(2, RoundingMode.HALF_UP));
        returnCase.setNetRefundAmount(securityRefund.add(refund).setScale(2, RoundingMode.HALF_UP));
        returnCase.setAmountDueFromCustomer(amountDue);
        returnCase.setLegacyPricingReviewRequired(false);
        returnCase.setSettlementMode(SettlementMode.AUTO);
        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
        ReturnCase saved = returnCaseRepository.save(returnCase);
        auditLogService.recordMutation("LEGACY_RETURN_PRICING_REVIEWED", "return", saved.getId(),
            saved.getFacility().getId(), null, toResponse(saved));
        notificationService.createNotification(saved.getCustomer().getId(), NotificationType.RETURN,
            "Business đã duyệt quyết toán trả sớm", "Bạn có thể xác nhận biên bản trả kho để tiếp tục hoàn tiền.", saved.getId());
        return toResponse(saved);
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

        BigDecimal totalDeductions = safeDamage
            .add(safeCleaning)
            .add(safeLostItem)
            .add(safeOverdue)
            .add(safeOutstanding)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal deposit = returnCase.getDepositAmount() != null ? returnCase.getDepositAmount() : BigDecimal.ZERO;
        var activePricing = activeReturnCalculation(returnCase);
        boolean legacyPricingReview = returnCase.isActiveReturnRepricingRequired() && activePricing == null;
        boolean activeRepricing = activePricing != null && activePricing.recalculationRequired();
        if (returnCase.getRental().getStatus() == RentalStatus.setup) {
            boolean goodsMovedIn = returnCase.getRental().getMoveInCompletedAt() != null
                || returnCase.getRental().getInventoryVerifiedAt() != null;
            returnCase.setReturnType(goodsMovedIn ? ReturnType.RETURN_IN_SETUP : ReturnType.CANCELLED_IN_SETUP);
            returnCase.setSettlementMode(SettlementMode.MANUAL_REVIEW);
        } else if (!legacyPricingReview && returnCase.getReturnType() == null) {
            returnCase.setReturnType(activeRepricing ? ReturnType.EARLY_RETURN : ReturnType.NORMAL);
        }
        BigDecimal earlyTerminationCharge = returnCase.isSetupEarlyExit()
            ? zeroIfNull(returnCase.getEarlyTerminationFee())
            : (!legacyPricingReview && activeRepricing ? activePricing.totalRentalCharge() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        BigDecimal rentalRefund = returnCase.isSetupEarlyExit()
            ? zeroIfNull(returnCase.getRentalRefundCredit())
            : (!legacyPricingReview && activeRepricing ? activePricing.rentalRefundAmount() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        BigDecimal securityRefund = deposit.subtract(totalDeductions).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        totalDeductions = totalDeductions.add(earlyTerminationCharge).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountDue = totalDeductions.subtract(deposit).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netRefund = securityRefund.add(rentalRefund).setScale(2, RoundingMode.HALF_UP);
        if (returnCase.isSetupEarlyExit()) {
            BigDecimal setupBalance = deposit.add(rentalRefund).subtract(totalDeductions);
            netRefund = setupBalance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            amountDue = setupBalance.negate().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        requireSettlementLimit(totalDeductions);

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
        returnCase.setEarlyTerminationCharge(earlyTerminationCharge);
        returnCase.setRentalRefundAmount(rentalRefund);
        returnCase.setTotalDeductions(totalDeductions);
        returnCase.setNetRefundAmount(netRefund);
        returnCase.setAmountDueFromCustomer(amountDue);
        returnCase.setLegacyPricingReviewRequired(legacyPricingReview);
        if (legacyPricingReview) {
            returnCase.setSettlementMode(SettlementMode.MANUAL_REVIEW);
        }

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

        returnCase.setStatus(legacyPricingReview
            ? ReturnCaseStatus.awaiting_business_review
            : ReturnCaseStatus.awaiting_customer_confirmation);
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
            legacyPricingReview
                ? "Hồ sơ cũ chưa có bảng giá trả sớm bất biến; Business cần duyệt trước khi quyết toán."
                : "Nhân viên đã hoàn thành biên bản nghiệm thu gian " + saved.getStorageUnit().getCode() + ". Vui lòng xác nhận quyết toán.",
            saved.getId()
        );
        if (legacyPricingReview) {
            notificationService.createBusinessNotifications(
                NotificationType.RETURN,
                "Cần duyệt bảng giá trả sớm của hồ sơ cũ",
                "Reservation chưa có early-termination pricing snapshot. Business phải nhập khoản tính/hoàn thủ công trước khi khách xác nhận.",
                saved.getId()
            );
        }

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
            var reconciliation = requireFinance();
            if (returnCase.getAmountDueFromCustomer() != null && returnCase.getAmountDueFromCustomer().compareTo(BigDecimal.ZERO) > 0) {
                returnCase.setStatus(ReturnCaseStatus.payment_due);
            } else if (returnCase.getNetRefundAmount() != null && returnCase.getNetRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
                returnCase.setStatus(ReturnCaseStatus.refund_pending);
            } else {
                reconciliation.settle(returnCase, actor.userId(), "OFFSET:" + returnCase.getId(), false);
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

        var reconciliation = requireFinance();
        reconciliation.requireCurrent(returnCase);
        if (returnCase.getAmountDueFromCustomer() != null && returnCase.getAmountDueFromCustomer().compareTo(BigDecimal.ZERO) > 0) {
            returnCase.setStatus(ReturnCaseStatus.payment_due);
        } else if (returnCase.getNetRefundAmount() != null && returnCase.getNetRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
            returnCase.setStatus(ReturnCaseStatus.refund_pending);
        } else {
            reconciliation.settle(returnCase, returnCase.getCustomer().getId(), "AUTO_OFFSET:" + returnCase.getId(), false);
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

        BigDecimal totalDeductions = safeDamage
            .add(safeCleaning)
            .add(safeLostItem)
            .add(safeOverdue)
            .add(safeOutstanding)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal deposit = returnCase.getDepositAmount() != null ? returnCase.getDepositAmount() : BigDecimal.ZERO;
        var activePricing = activeReturnCalculation(returnCase);
        BigDecimal calculatedEarlyTerminationCharge = activePricing == null
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : activePricing.totalRentalCharge();
        BigDecimal calculatedRentalRefund = activePricing == null
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : activePricing.rentalRefundAmount();
        if (returnCase.isSetupEarlyExit()) safeOverdue = BigDecimal.ZERO;
        BigDecimal earlyTerminationCharge = returnCase.isSetupEarlyExit()
            ? zeroIfNull(returnCase.getEarlyTerminationFee())
            : (returnCase.getEarlyTerminationCharge() != null ? returnCase.getEarlyTerminationCharge() : calculatedEarlyTerminationCharge);
        BigDecimal rentalRefund = returnCase.isSetupEarlyExit()
            ? zeroIfNull(returnCase.getRentalRefundCredit())
            : (returnCase.getRentalRefundAmount() != null ? returnCase.getRentalRefundAmount() : calculatedRentalRefund);
        BigDecimal securityRefund = deposit.subtract(totalDeductions).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        totalDeductions = totalDeductions.add(earlyTerminationCharge).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netRefund = securityRefund.add(rentalRefund).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountDue = totalDeductions.subtract(deposit).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (returnCase.isSetupEarlyExit()) {
            BigDecimal setupBalance = deposit.add(rentalRefund).subtract(totalDeductions);
            netRefund = setupBalance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            amountDue = setupBalance.negate().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }

        requireSettlementLimit(totalDeductions);
        returnCase.setReviewedBy(manager);
        returnCase.setReviewedAt(Instant.now());
        returnCase.setManagerResolutionNote(request.resolutionNote());

        returnCase.setDamageFee(safeDamage);
        returnCase.setCleaningFee(safeCleaning);
        returnCase.setLostItemFee(safeLostItem);
        returnCase.setOverdueFee(safeOverdue);
        returnCase.setOutstandingFee(safeOutstanding);
        returnCase.setEarlyTerminationCharge(earlyTerminationCharge);
        returnCase.setRentalRefundAmount(rentalRefund);
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
            requireFinance().settle(returnCase, actor.userId(), "OFFSET:" + returnCase.getId(), false);
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
        boolean business = actor != null && actor.hasRole(RoleCode.BUSINESS);
        if (actor == null || (!actor.hasRole(RoleCode.STAFF) && !actor.hasRole(RoleCode.BUSINESS) && !actor.hasRole(RoleCode.ADMIN))) {
            throw ApiExceptions.forbidden("Only Staff or Business Operations can execute a return settlement");
        }
        authorizationService.require(actor, SystemPermission.EXECUTE_REFUNDS);
        if (request == null) throw ApiExceptions.validation("Refund details are required", null);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        if (business) {
            facilityScopeService.assertCanManage(actor, returnCase.getFacility().getId());
        } else {
            facilityScopeService.assertCanOperate(actor, returnCase.getFacility().getId());
        }

        lockRecoveryReturn(returnCase);
        var recoveryGuard = recoveryGuard();
        if (recoveryGuard != null) {
            recoveryGuard.requireManualSettlement(returnCase);
        }
        boolean vnpay = "VNPAY".equalsIgnoreCase(request.paymentMethod())
            || "VNPAY_GATEWAY".equalsIgnoreCase(request.paymentMethod());
        String settlementReference;
        if (vnpay) {
            if (vnpayService == null || paymentRepository == null || request.originalPaymentId() == null) {
                throw ApiExceptions.conflict("Original VNPay payment is required for a gateway refund");
            }
            var originalPayment = paymentRepository.findById(request.originalPaymentId())
                .orElseThrow(() -> ApiExceptions.notFound("Original payment was not found"));
            if (originalPayment.getReservation() == null
                || !originalPayment.getReservation().getId().equals(returnCase.getRental().getReservation().getId())) {
                throw ApiExceptions.conflict("Original payment does not belong to this rental");
            }
            settlementReference = vnpayService.executeApprovedReturnRefund(
                actor, originalPayment.getId(), returnCase.getNetRefundAmount(),
                request.notes() == null || request.notes().isBlank() ? "StorageHub return refund" : request.notes(),
                "127.0.0.1"
            );
            if (finance() != null) {
                finance().requireCurrent(returnCase);
                finance().settle(returnCase, actor.userId(), settlementReference, true);
            }
        } else {
            if (!Boolean.TRUE.equals(request.confirmed()) || request.amount() == null) {
                throw ApiExceptions.validation("Vui lòng xác nhận số tiền thực hoàn và đối chiếu biên lai trước khi ghi nhận.", null);
            }
            if (returnCase.getStatus() != ReturnCaseStatus.refund_pending
                || returnCase.getNetRefundAmount() == null || returnCase.getNetRefundAmount().signum() <= 0
                || returnCase.getNetRefundAmount().compareTo(request.amount()) != 0) {
                throw ApiExceptions.conflict("Số tiền thực hoàn không khớp với bảng kê đang chờ hoàn tiền.");
            }
            if (request.transactionReference() == null || request.transactionReference().isBlank()
                || request.paymentMethod() == null || request.paymentMethod().isBlank() || request.receiptFileId() == null) {
                throw ApiExceptions.validation("A real refund transaction, payment method and receipt are required", null);
            }
            if (finance() == null) {
                throw ApiExceptions.conflict("Financial reconciliation is unavailable; the return case cannot be completed");
            }
            finance().receipt(actor, returnCase, request.paymentMethod(), request.transactionReference(), request.receiptFileId());
            finance().settle(returnCase, actor.userId(), request.transactionReference(), true);
            settlementReference = request.transactionReference();
        }

        returnCase.setStatus(ReturnCaseStatus.completed);
        returnCase.setCompletedAt(Instant.now());
        returnCase.setSettlementPaidAt(Instant.now());
        returnCase.setSettlementPaymentId(settlementReference);

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
    public ReturnCaseResponse customerPaySettlement(ActorPrincipal actor,UUID returnId,CustomerPaySettlementRequest request) {
        throw ApiExceptions.conflict("Vui lòng hoàn tất thanh toán tại cơ sở bằng biên lai do nhân viên xác nhận hoặc qua cổng thanh toán được chấp thuận.");
    }

    @Transactional
    public ReturnCaseResponse recordSettlement(ActorPrincipal actor,UUID returnId,com.storagehub.api.returns.StaffReturnSettlementRequest request) {
        if (actor == null || (!actor.hasRole(RoleCode.STAFF) && !actor.hasRole(RoleCode.ADMIN))) {
            throw ApiExceptions.forbidden("Only Staff can record a return settlement");
        }
        if (request == null || request.confirmed() == null || !request.confirmed()) {
            throw ApiExceptions.validation("Settlement confirmation is required", null);
        }
        var rc=returnCaseRepository.findById(returnId).orElseThrow(()->ApiExceptions.notFound("Return case not found"));
        facilityScopeService.assertCanOperate(actor, rc.getFacility().getId());
        if (rc.getStatus() == ReturnCaseStatus.completed) {
            BigDecimal settledAmount = zeroIfNull(rc.getAmountDueFromCustomer()).signum() > 0
                ? rc.getAmountDueFromCustomer() : rc.getNetRefundAmount();
            if (settledAmount == null || request.amount() == null || settledAmount.compareTo(request.amount()) != 0
                || !requireFinance().recordedReceiptMatches(rc, request.paymentMethod(), request.transactionReference(), request.receiptFileId())) {
                throw ApiExceptions.conflict("Yêu cầu gửi lại không khớp với biên lai quyết toán đã hoàn tất.");
            }
            return toResponse(rc);
        }
        if (rc.getStatus() != ReturnCaseStatus.payment_due && rc.getStatus() != ReturnCaseStatus.refund_pending) {
            throw ApiExceptions.conflict("The return case has no outstanding collection or refund");
        }
        BigDecimal expectedAmount = rc.getStatus() == ReturnCaseStatus.payment_due ? rc.getAmountDueFromCustomer() : rc.getNetRefundAmount();
        if (expectedAmount == null || request.amount() == null || expectedAmount.compareTo(request.amount()) != 0) {
            throw ApiExceptions.conflict("Số tiền quyết toán không khớp với bảng kê đã xác nhận.");
        }
        lockRecoveryReturn(rc);
        var guard=recoveryGuard();
        if(guard!=null) {
            guard.requireManualSettlement(rc);
        }
        if (finance() == null) {
            throw ApiExceptions.conflict("Financial reconciliation is unavailable; the return case cannot be settled");
        }
        if(!finance().recordedReceiptMatches(rc,request.paymentMethod(),request.transactionReference(),request.receiptFileId())) {
            finance().receipt(actor,rc,request.paymentMethod(),request.transactionReference(),request.receiptFileId());
            finance().settle(rc,actor.userId(),request.transactionReference(),true);
        }
        rc.setStatus(ReturnCaseStatus.completed);
        rc.setCompletedAt(Instant.now());
        rc.setSettlementPaidAt(Instant.now());
        rc.setSettlementPaymentId(request.transactionReference());
        returnCaseRepository.saveAndFlush(rc);
        finalizeRentalAndUnit(rc);
        auditLogService.recordMutation("RETURN_FINANCIAL_SETTLED","return",returnId,rc.getFacility().getId(),null,toResponse(rc));
        notificationService.createNotification(rc.getCustomer().getId(),NotificationType.PAYMENT,"Đã ghi nhận quyết toán trả kho","Nhân viên đã đối chiếu biên lai và hoàn tất thu hoặc hoàn tiền cho hồ sơ trả kho.",returnId);
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
            var returnType = returnCase.getReturnType();
            rental.setEndReason(returnType == null ? com.storagehub.domain.model.RentalEndReason.NORMAL : switch (returnType) {
                case CANCELLED_IN_SETUP -> com.storagehub.domain.model.RentalEndReason.CANCELLED_IN_SETUP;
                case RETURN_IN_SETUP -> com.storagehub.domain.model.RentalEndReason.RETURN_IN_SETUP;
                case EARLY_RETURN -> com.storagehub.domain.model.RentalEndReason.EARLY_RETURN;
                case NORMAL -> com.storagehub.domain.model.RentalEndReason.NORMAL;
            });
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

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
            rc.getReturnType(),
            rc.getSettlementMode(),
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
            rc.getRental().isSetupExitReviewRequired(),
            rc.getRental().getMoveInCompletedAt(),
            settlementReceipt,
            overdueSettlement,
            settlementBlocker,
            earlyTerminationPolicy(rc),
            activeReturnPolicy(rc),
            rc.isLegacyPricingReviewRequired(),
            rc.getLegacyPricingSnapshotJson() != null && !rc.getLegacyPricingSnapshotJson().isBlank(),
            rc.getLegacyPricingEvidenceFileId(),
            rc.isSetupExitRequested(), rc.isSetupEarlyExit(), rc.getSetupReturnedAt(),
            rc.getSetupEarlyExitPolicyVersion(), rc.getRentalRefundCredit(), rc.getEarlyTerminationFee()
        );
    }

    private boolean hasPricingTiers(Rental rental) {
        if (reservationPricingSnapshotRepository == null) return false;
        ReservationPricingSnapshot snapshot = rental == null || rental.getReservation() == null
            ? null : reservationPricingSnapshotRepository.findByReservation_Id(rental.getReservation().getId()).orElse(null);
        return snapshot != null && snapshot.getEarlyTerminationPricingSnapshotJson() != null
            && !snapshot.getEarlyTerminationPricingSnapshotJson().isBlank()
            && !"[]".equals(snapshot.getEarlyTerminationPricingSnapshotJson().trim());
    }

    private ReturnCaseResponse.EarlyTerminationSummary earlyTerminationPolicy(ReturnCase returnCase) {
        ReservationPricingSnapshot snapshot = reservationPricingSnapshot(returnCase);
        var calculation = setupExitCalculation(returnCase);
        if (snapshot == null || calculation == null) return null;
        return new ReturnCaseResponse.EarlyTerminationSummary(
            calculation.type().name(),
            calculation.earlyTerminationFee(),
            calculation.billableSetupDays(),
            calculation.usedGoodsCharge(),
            calculation.totalRentalCharge(),
            calculation.rentalRefundAmount(),
            snapshot.getEarlyTerminationNoticeDays()
        );
    }

    private EarlyTerminationPolicy.SetupExitCalculation setupExitCalculation(ReturnCase returnCase) {
        Rental rental = returnCase == null ? null : returnCase.getRental();
        ReservationPricingSnapshot snapshot = reservationPricingSnapshot(returnCase);
        if (rental == null || !rental.isSetupExitReviewRequired() || snapshot == null) return null;

        LocalDate setupStart = rental.getSetupStartedAt() == null
            ? returnCase.getRequestedAt().atZone(BusinessTime.ZONE).toLocalDate()
            : rental.getSetupStartedAt().atZone(BusinessTime.ZONE).toLocalDate();
        LocalDate requested = returnCase.getRequestedAt().atZone(BusinessTime.ZONE).toLocalDate();
        int usedDays = Math.max(1, (int) ChronoUnit.DAYS.between(setupStart, requested) + 1);
        boolean goodsMovedIn = rental.getMoveInCompletedAt() != null || rental.getInventoryVerifiedAt() != null;
        return EarlyTerminationPolicy.setupExit(
            snapshot.getNetRentalAmount(),
            snapshot.getReservationDepositAmount(),
            snapshot.getMonthlyPrice(),
            snapshot.getRentalMonths(),
            snapshot.getFreeSetupDays(),
            usedDays,
            goodsMovedIn,
            snapshot.getEarlyTerminationFeeCoefficient(),
            snapshot.getEarlyTerminationFeeCapMonths()
        );
    }

    private ReturnCaseResponse.ActiveReturnSummary activeReturnPolicy(ReturnCase returnCase) {
        var calculation = activeReturnCalculation(returnCase);
        if (calculation == null) return null;
        ReservationPricingSnapshot snapshot = reservationPricingSnapshot(returnCase);
        return new ReturnCaseResponse.ActiveReturnSummary(
            calculation.recalculationRequired(),
            calculation.completedRentalMonths(),
            calculation.pricingTierMonths(),
            calculation.setupDaysUsed(),
            calculation.chargeableSetupDays(),
            calculation.rentalDaysUsed(),
            calculation.noticeShortfallDays(),
            calculation.dailyPrice(),
            calculation.earlyTerminationFee(),
            calculation.noticeCharge(),
            calculation.totalRentalCharge(),
            calculation.rentalRefundAmount(),
            snapshot == null ? EarlyTerminationPolicy.DEFAULT_NOTICE_DAYS : snapshot.getEarlyTerminationNoticeDays()
        );
    }

    private EarlyTerminationPolicy.ActiveReturnCalculation activeReturnCalculation(ReturnCase returnCase) {
        if (returnCase == null || !returnCase.isActiveReturnRepricingRequired()) return null;
        Rental rental = returnCase.getRental();
        ReservationPricingSnapshot snapshot = reservationPricingSnapshot(returnCase);
        if (rental == null || snapshot == null || snapshot.getEarlyTerminationPricingSnapshotJson() == null
            || snapshot.getEarlyTerminationPricingSnapshotJson().isBlank()
            || "[]".equals(snapshot.getEarlyTerminationPricingSnapshotJson())) {
            return null;
        }
        List<EarlyTerminationPolicy.PricingTier> tiers = readPricingTiers(snapshot.getEarlyTerminationPricingSnapshotJson());
        LocalDate paidStart = rental.getStartDate();
        LocalDate setupStart = rental.getSetupStartedAt() == null
            ? paidStart.minusDays(snapshot.getFreeSetupDays())
            : rental.getSetupStartedAt().atZone(BusinessTime.ZONE).toLocalDate();
        LocalDate requestedAt = returnCase.getRequestedAt().atZone(BusinessTime.ZONE).toLocalDate();
        LocalDate returnDate = returnCase.getScheduledDate();
        long noticeGivenDays = Math.max(0, ChronoUnit.DAYS.between(requestedAt, returnDate));
        return EarlyTerminationPolicy.activeReturn(
            paidStart,
            returnDate,
            setupStart,
            snapshot.getRentalMonths(),
            snapshot.getFreeSetupDays(),
            snapshot.getMonthlyPrice(),
            snapshot.getNetRentalAmount(),
            snapshot.getReservationDepositAmount(),
            snapshot.getEarlyTerminationFeeCoefficient(),
            snapshot.getEarlyTerminationFeeCapMonths(),
            snapshot.getEarlyTerminationNoticeDays(),
            noticeGivenDays,
            tiers
        );
    }

    private ReservationPricingSnapshot reservationPricingSnapshot(ReturnCase returnCase) {
        if (returnCase == null || returnCase.getRental() == null
            || returnCase.getRental().getReservation() == null
            || reservationPricingSnapshotRepository == null) return null;
        return reservationPricingSnapshotRepository
            .findByReservation_Id(returnCase.getRental().getReservation().getId())
            .orElse(null);
    }

    private List<EarlyTerminationPolicy.PricingTier> readPricingTiers(String snapshotJson) {
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                snapshotJson, new TypeReference<List<Map<String, Object>>>() {}
            );
            return rows.stream()
                .map(row -> new EarlyTerminationPolicy.PricingTier(
                    ((Number) row.get("rentalMonths")).intValue(),
                    objectMapper.convertValue(row.get("monthlyPrice"), BigDecimal.class),
                    ((Number) row.get("freeSetupDays")).intValue()
                ))
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
