package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.returns.CustomerConfirmSettlementRequest;
import com.storagehub.api.returns.CustomerCreateReturnRequest;
import com.storagehub.api.returns.CustomerPaySettlementRequest;
import com.storagehub.api.returns.BusinessSetupExitDecisionRequest;
import com.storagehub.api.returns.BusinessLegacyPricingDecisionRequest;
import com.storagehub.api.returns.ManagerInventoryMismatchDecisionRequest;
import com.storagehub.api.returns.ManagerReviewDisputeRequest;
import com.storagehub.api.returns.ReturnCaseResponse;
import com.storagehub.api.returns.ReturnInspectionRequest;
import com.storagehub.api.returns.ReturnPropertyCheckRequest;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.api.returns.StaffCompleteRefundRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.CustomerDecision;
import com.storagehub.domain.model.LockInspectionResult;
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
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.MaintenanceTaskType;
import com.storagehub.domain.model.MaintenanceRecoveryType;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.rental.period.RentalPeriod;
import com.storagehub.service.rental.period.RentalPeriodResolver;
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
    private static final String NO_SHOW_RECOVERY_TASK_TITLE = "Thu hồi hàng do khách không đến ngày trả kho";
    @org.springframework.beans.factory.annotation.Value("${app.refund.processing-days:7}")
    private int refundProcessingDays = 7;
    @org.springframework.beans.factory.annotation.Value("${app.return.pallet-unit-price:300000}")
    private BigDecimal palletUnitPrice = BigDecimal.valueOf(300_000);

    private void markRefundPending(ReturnCase returnCase) {
        returnCase.setStatus(ReturnCaseStatus.refund_pending);
        if (returnCase.getRefundExpectedAt() == null) {
            returnCase.setRefundExpectedAt(Instant.now().plus(java.time.Duration.ofDays(refundProcessingDays)));
        }
    }

    @Autowired
    private AccessCredentialService accessCredentialService;
    @Autowired
    private MismatchUnitRelease mismatchUnitRelease;

    @org.springframework.beans.factory.annotation.Autowired
    private ViolationRecoveryWorkflow violationRecovery;

    @org.springframework.beans.factory.annotation.Autowired
    private WorkflowBusinessCalendar recoveryCalendar;
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.overdue.RecoverySettlementGuard> recoveryGuards;
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.ledger.ReturnFinancialReconciliation> returnFinances;
    @Autowired
    private ReservationPricingSnapshotRepository reservationPricingSnapshotRepository;
    @Autowired
    private RentalPeriodResolver rentalPeriods;
    @Autowired(required = false)
    private com.storagehub.domain.repo.CheckInRepository inventoryCheckInRepository;
    @Autowired(required = false)
    private PalletInventoryService palletInventoryService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private FileAssetRepository fileAssetRepository;
    @Autowired
    private UserFacilityScopeRepository userFacilityScopeRepository;
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
    @Autowired(required = false)
    private MaintenanceTaskRepository maintenanceTaskRepository;
    @org.springframework.beans.factory.annotation.Value("${storagehub.return.no-show-grace-hours:24}")
    private long noShowGraceHours = 24;
    @org.springframework.beans.factory.annotation.Value("${storagehub.return.no-show-recovery-sla-hours:24}")
    private long noShowRecoverySlaHours = 24;

    @Transactional
    public ReturnCaseResponse createCustomerReturnRequest(
        ActorPrincipal actor,
        UUID rentalId,
        CustomerCreateReturnRequest request
    ) {
        return createReturnRequest(actor, rentalId, request, false);
    }

    @Transactional
    public ReturnCaseResponse createInventoryReturnAtSite(ActorPrincipal actor, UUID rentalId, CustomerCreateReturnRequest request) {
        if (!actor.hasRole(RoleCode.STAFF)) throw ApiExceptions.forbidden("Chỉ Staff được ghi nhận yêu cầu trả kho tại cơ sở.");
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        if (request.returnType() != ReturnType.INVENTORY_MISMATCH) throw ApiExceptions.conflict("Chỉ áp dụng cho trả kho do kiểm kê sai lệch.");
        return createReturnRequest(actor, rentalId, request, true);
    }

    private ReturnCaseResponse createReturnRequest(ActorPrincipal actor, UUID rentalId, CustomerCreateReturnRequest request, boolean atSite) {

        Rental rental = rentalRepository.findByIdForUpdate(rentalId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found for this customer"));
        if (atSite) facilityScopeService.assertCanOperate(actor, rental.getFacility().getId());
        if (!atSite && !rental.getCustomer().getId().equals(actor.userId()))
            throw ApiExceptions.notFound("Rental was not found for this customer");

        User staff = atSite ? userRepository.findById(actor.userId()).orElse(null) : null;
        return createReturnRequest(rental, request, staff);
    }

    ReturnCaseResponse createInventoryReturn(Rental rental, CustomerCreateReturnRequest request, User staff) {
        return createReturnRequest(rental, request, staff);
    }

    private ReturnCaseResponse createReturnRequest(Rental rental, CustomerCreateReturnRequest request, User staff) {
        if (staff == null && inventoryCheckInRepository != null && rental.getReservation() != null
            && inventoryCheckInRepository.findByReservation_Id(rental.getReservation().getId())
                .map(com.storagehub.domain.model.CheckIn::hasPendingInventoryRefusal).orElse(false))
            throw ApiExceptions.conflict("Biên bản từ chối ký đang chờ quản lý xem xét.");
        var existingReturns = returnCaseRepository.findByRental_Id(rental.getId());
        var activeReturn = existingReturns.stream().filter(r -> r.getStatus() != ReturnCaseStatus.completed).findFirst();
        if (activeReturn.isPresent()) return toResponse(activeReturn.get());
        boolean inventoryMismatchReturn = request.returnType() == ReturnType.INVENTORY_MISMATCH;
        if (inventoryMismatchReturn && rental.getInventoryRemovalPublishedAt() != null)
            validateRemovalDate(rental, request.scheduledDate());
        if (rental.isInventoryPending() && rental.getInventoryEscalatedAt() != null) {
            throw ApiExceptions.conflict("Hồ sơ đã chuyển sang chờ thu hồi. Vui lòng liên hệ cơ sở.");
        }
        if (!inventoryMismatchReturn && rental.isInventoryPending() && rental.getInventoryRemovalDeadlineAt() != null) {
            boolean decidedReturn = inventoryCheckInRepository != null && rental.getReservation() != null
                && inventoryCheckInRepository.findByReservation_Id(rental.getReservation().getId())
                    .map(ci -> ci.getInventoryResolutionStatus() == com.storagehub.domain.model.InventoryResolutionStatus.RETURN_REQUESTED)
                    .orElse(false);
            if (!decidedReturn) {
                throw ApiExceptions.conflict("Vui lòng đặt lịch trả kho từ kết quả kiểm kê hàng hóa.");
            }
            inventoryMismatchReturn = true;
        }
        if (inventoryMismatchReturn
            && rental.getStatus() != RentalStatus.setup
            && !rental.isInventoryPending()) {
            throw ApiExceptions.conflict("Hồ sơ không còn ở bước kiểm kê hàng hóa để kết thúc do sai lệch.");
        }
        boolean setupExit = inventoryMismatchReturn || inSetupReturnWindow(rental);
        if (rental.getStatus() != RentalStatus.setup && rental.getStatus() != RentalStatus.active) {
            throw ApiExceptions.conflict("Chỉ hồ sơ đang trong thời gian chuẩn bị hoặc đang thuê mới được yêu cầu trả kho");
        }
        var recoveryGuard=recoveryGuard();
        if(recoveryGuard!=null) {
            recoveryGuard.requireNoActiveRecovery(rental);
        }

        User customer = staff == null ? userRepository.findById(rental.getCustomer().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Customer user not found")) : rental.getCustomer();

        // Only verified check-in funds establish the available security deposit.
        BigDecimal depositAmount = securityDeposit(rental);

        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        Instant now = Instant.now();
        if (request.scheduledDate() != null) {
            if (staff == null && !request.scheduledDate().isAfter(today)) {
                throw ApiExceptions.validation("Ngày hẹn trả kho phải sau ngày tạo đơn trả kho ít nhất 1 ngày.", null);
            }
            if (request.scheduledDate().isBefore(today)) {
                throw ApiExceptions.validation("Ngày trả kho không được ở quá khứ.", null);
            }
        }
        if (inventoryMismatchReturn) InventoryDeadlinePolicy.requireRemovalDate(rental, request.scheduledDate(), now);

        ReturnCase returnCase = new ReturnCase();
        returnCase.setRental(rental);
        returnCase.setFacility(rental.getFacility());
        returnCase.setStorageUnit(rental.getStorageUnit());
        returnCase.setCustomer(customer);
        returnCase.setRequestedBy(staff == null ? customer : staff);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setRequestedAt(now);
        returnCase.setScheduledDate(request.scheduledDate() != null ? request.scheduledDate() : today);
        returnCase.setRecoveryEligibleAt(inventoryMismatchReturn && rental.getInventoryRemovalDeadlineAt() != null
            ? rental.getInventoryRemovalDeadlineAt() : noShowRecoveryEligibleAt(returnCase.getScheduledDate()));
        returnCase.setCustomerNotes(request.notes());
        returnCase.setDepositAmount(depositAmount);
        returnCase.setSetupExitRequested(setupExit);
        if (setupExit) {
            returnCase.setReturnType(inventoryMismatchReturn
                ? ReturnType.INVENTORY_MISMATCH : ReturnType.RETURN_IN_SETUP);
            returnCase.setSettlementMode(SettlementMode.MANUAL_REVIEW);
            if (inventoryMismatchReturn) {
                returnCase.setReturnWindowDays(InventoryTimelinePolicy.resolveReturnWindowDays(rental, null));
                returnCase.setReturnExtensionDays(rental.getInventoryReturnExtensionDays());
                returnCase.setReturnExtensionCount(rental.getInventoryReturnExtensionCount());
                returnCase.setReturnPolicyVersion(rental.getInventoryReturnPolicyVersion());
            }
        } else {
            classifyScheduledReturn(returnCase);
        }

        ReturnCase saved = returnCaseRepository.save(returnCase);

        if (setupExit) {
            rental.setSetupExitReviewRequired(true);
            notificationService.createBusinessNotifications(
                NotificationType.RETURN,
                "Yêu cầu trả kho trong thời gian chuẩn bị",
                "Đơn " + rental.getReservation().getReservationCode()
                    + " yêu cầu trả kho trong thời gian chuẩn bị. Staff cần xác nhận kho đã trống, kiểm kê và lập bảng kê theo điều khoản đã chấp nhận.",
                saved.getId()
            );
        }
        rental.setStatus(RentalStatus.return_requested);
        rentalRepository.save(rental);

        auditLogService.recordMutation(
            staff == null ? "CUSTOMER_RETURN_REQUESTED" : "STAFF_INVENTORY_RETURN_REQUESTED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        notificationService.createNotification(
            customer.getId(),
            NotificationType.RETURN,
            staff != null && inventoryMismatchReturn ? "Cơ sở đã tạo hồ sơ trả kho sau kiểm kê" : "Yêu cầu trả kho đã được ghi nhận",
            staff != null && inventoryMismatchReturn
                ? "Cơ sở đã ghi nhận phương án trả kho sau kiểm kê cho gian " + rental.getStorageUnit().getCode() + ". Vui lòng theo dõi lịch nghiệm thu và quyết toán."
                : "Yêu cầu trả kho cho gian " + rental.getStorageUnit().getCode() + " đã được tiếp nhận.",
            saved.getId()
        );

        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(),
            NotificationType.RETURN,
            "Yêu cầu trả kho mới",
            (staff != null && inventoryMismatchReturn ? "Nhân viên đã ghi nhận tại cơ sở phương án trả kho của " : "Khách hàng ")
                + customer.getFullName() + " cho gian " + rental.getStorageUnit().getCode() + ". Vui lòng kiểm tra và nghiệm thu kho.",
            saved.getId()
        );

        return toResponse(saved);
    }

    /**
     * Creates the same inventory-mismatch return case when the customer and
     * Staff agree on the return at the facility. The customer remains the
     * owner of the rental, while the Staff account is recorded as requester.
     */
    @Transactional
    public ReturnCaseResponse createStaffInventoryMismatchReturnRequest(
        ActorPrincipal actor,
        UUID rentalId,
        CustomerCreateReturnRequest request
    ) {
        if (!actor.hasRole(RoleCode.STAFF)) {
            throw ApiExceptions.forbidden("Chỉ nhân viên được ghi nhận yêu cầu trả kho tại cơ sở.");
        }
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        Rental rental = rentalRepository.findByIdForUpdate(rentalId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found"));
        facilityScopeService.assertCanOperate(actor, rental.getFacility().getId());
        if (request == null || request.returnType() != ReturnType.INVENTORY_MISMATCH || request.scheduledDate() == null) {
            throw ApiExceptions.validation("Yêu cầu trả kho do sai lệch cần có ngày trả dự kiến.", null);
        }
        var existingReturns = returnCaseRepository.findByRental_Id(rentalId);
        var activeReturn = existingReturns.stream().filter(r -> r.getStatus() != ReturnCaseStatus.completed).findFirst();
        if (activeReturn.isPresent()) return toResponse(activeReturn.get());
        if (rental.getStatus() != RentalStatus.setup && rental.getStatus() != RentalStatus.active) {
            throw ApiExceptions.conflict("Hồ sơ không còn ở bước có thể tạo yêu cầu trả kho.");
        }
        if (rental.getInventoryRemovalPublishedAt() != null) {
            validateRemovalDate(rental, request.scheduledDate());
        }
        if (!rental.isInventoryPending() && rental.getInventoryRemovalPublishedAt() == null) {
            throw ApiExceptions.conflict("Hồ sơ không còn ở bước kiểm kê hàng hóa để kết thúc do sai lệch.");
        }
        InventoryDeadlinePolicy.requireRemovalDate(rental, request.scheduledDate(), Instant.now());
        var recoveryGuard = recoveryGuard();
        if (recoveryGuard != null) recoveryGuard.requireNoActiveRecovery(rental);

        User customer = rental.getCustomer();
        User staff = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Staff user not found"));
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        if (request.scheduledDate().isBefore(today)) {
            throw ApiExceptions.validation("Ngày trả kho không được ở quá khứ.", null);
        }
        Instant now = Instant.now();
        ReturnCase returnCase = new ReturnCase();
        returnCase.setRental(rental);
        returnCase.setFacility(rental.getFacility());
        returnCase.setStorageUnit(rental.getStorageUnit());
        returnCase.setCustomer(customer);
        returnCase.setRequestedBy(staff);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setRequestedAt(now);
        returnCase.setScheduledDate(request.scheduledDate());
        returnCase.setRecoveryEligibleAt(rental.getInventoryRemovalDeadlineAt() == null
            ? noShowRecoveryEligibleAt(request.scheduledDate()) : rental.getInventoryRemovalDeadlineAt());
        returnCase.setCustomerNotes(request.notes());
        returnCase.setDepositAmount(securityDeposit(rental));
        returnCase.setSetupExitRequested(true);
        returnCase.setReturnType(ReturnType.INVENTORY_MISMATCH);
        returnCase.setSettlementMode(SettlementMode.MANUAL_REVIEW);
        returnCase.setReturnWindowDays(InventoryTimelinePolicy.resolveReturnWindowDays(rental, null));
        returnCase.setReturnExtensionDays(rental.getInventoryReturnExtensionDays());
        returnCase.setReturnExtensionCount(rental.getInventoryReturnExtensionCount());
        returnCase.setReturnPolicyVersion(rental.getInventoryReturnPolicyVersion());

        ReturnCase saved = returnCaseRepository.save(returnCase);
        rental.setSetupExitReviewRequired(true);
        rental.setStatus(RentalStatus.return_requested);
        rentalRepository.save(rental);
        notificationService.createBusinessNotifications(
            NotificationType.RETURN,
            "Yêu cầu trả kho do kiểm kê không phù hợp",
            "Đơn " + rental.getReservation().getReservationCode()
                + " được nhân viên ghi nhận yêu cầu trả kho sau khi khách xác nhận tại cơ sở.",
            saved.getId()
        );
        auditLogService.recordMutation(
            "STAFF_INVENTORY_RETURN_REQUESTED", "return", saved.getId(), saved.getFacility().getId(),
            Map.of("requestedBy", staff.getId(), "customerId", customer.getId()), toResponse(saved)
        );
        notificationService.createNotification(
            customer.getId(), NotificationType.RETURN,
            "Đã ghi nhận yêu cầu trả kho",
            "Nhân viên đã ghi nhận yêu cầu trả kho do kết quả kiểm kê không phù hợp cho gian "
                + rental.getStorageUnit().getCode() + ".",
            saved.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(), NotificationType.RETURN,
            "Đã tạo yêu cầu trả kho từ bước kiểm kê",
            "Đơn " + rental.getReservation().getReservationCode()
                + " đã được nhân viên ghi nhận phương án trả kho tại cơ sở.",
            saved.getId()
        );
        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse updateCustomerReturnRequest(ActorPrincipal actor, UUID returnId, CustomerCreateReturnRequest request) {
        if (!actor.hasRole(com.storagehub.domain.model.RoleCode.CUSTOMER)) throw ApiExceptions.forbidden("Chỉ khách hàng được sửa yêu cầu trả kho.");
        ReturnCase record = returnCaseRepository.findOwnedForUpdate(returnId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu trả kho của bạn."));
        boolean fixedDeadline = record.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && record.getRental().getInventoryRemovalDeadlineAt() != null;
        if (ReturnAttendancePolicy.applies(record) && record.getReturnArrivedAt() != null)
            throw ApiExceptions.conflict("Khách đã được tiếp nhận. Không thể đổi lịch trả kho.");
        if (fixedDeadline && (record.getRental().getActualReturnedAt() != null || record.getReadyForInspectionAt() != null))
            throw ApiExceptions.conflict("Gian kho đã bàn giao. Không thể đổi lịch trả kho.");
        if (record.getStatus() != ReturnCaseStatus.requested
            && !(fixedDeadline && record.getStatus() == ReturnCaseStatus.scheduled)) {
            throw ApiExceptions.conflict("Yêu cầu đã được nhân viên xử lý, không thể đổi ngày hoặc lý do.");
        }
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        LocalDate requestedDate = record.getRequestedAt() != null
            ? record.getRequestedAt().atZone(BusinessTime.ZONE).toLocalDate()
            : today;
        LocalDate minAllowed = record.getReturnType() == ReturnType.INVENTORY_MISMATCH ? today.plusDays(1)
            : today.isAfter(requestedDate) ? today : requestedDate.plusDays(1);
        if (request.scheduledDate().isBefore(today)) throw ApiExceptions.validation("Ngày trả kho không được ở quá khứ.", null);
        if (request.scheduledDate().isBefore(minAllowed)) throw ApiExceptions.validation("Ngày hẹn trả kho phải sau ngày tạo đơn trả kho ít nhất 1 ngày.", null);
        if (request.notes() == null || request.notes().isBlank()) throw ApiExceptions.validation("Vui lòng nhập lý do trả kho.", null);
        if (fixedDeadline) InventoryDeadlinePolicy.requireRemovalDate(record.getRental(), request.scheduledDate(), Instant.now());
        var before = toResponse(record);
        if (record.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && record.getRental().getInventoryRemovalPublishedAt() != null)
            validateRemovalDate(record.getRental(), request.scheduledDate());
        record.setScheduledDate(request.scheduledDate());
        record.setRecoveryEligibleAt(noShowRecoveryEligibleAt(request.scheduledDate()));
        if (record.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && record.getRental().getInventoryRemovalDeadlineAt() != null)
            record.setRecoveryEligibleAt(record.getRental().getInventoryRemovalDeadlineAt());
        record.setNoShowAt(null);
        record.setRecoveryTaskCreatedAt(null);
        record.setRecoveryNotes(null);
        if (!record.isSetupExitRequested()) {
            classifyScheduledReturn(record);
        }
        record.setCustomerNotes(request.notes().trim());
        ReturnCase saved = returnCaseRepository.saveAndFlush(record);
        auditLogService.recordMutation("CUSTOMER_RETURN_REQUEST_UPDATED", "return", saved.getId(), saved.getFacility().getId(), before, toResponse(saved));
        if (fixedDeadline) {
            notificationService.createFacilityOperationsNotifications(saved.getFacility().getId(), NotificationType.RETURN,
                "Khách đã đổi lịch trả kho do kiểm kê sai lệch",
                "Ngày hẹn mới của gian " + saved.getStorageUnit().getCode() + " là " + saved.getScheduledDate() + ".", saved.getId());
        }
        return toResponse(saved);
    }

    @Transactional
    public ReturnCaseResponse setCustomerClearance(ActorPrincipal actor, UUID id, boolean cleared, long revision) {
        if (!actor.hasRole(com.storagehub.domain.model.RoleCode.CUSTOMER))
            throw ApiExceptions.forbidden("Chỉ chủ hợp đồng được cập nhật xác nhận dọn kho.");
        var rc = returnCaseRepository.findByIdAndCustomer_Id(id, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu trả kho của bạn."));
        lockRecoveryReturn(rc);
        requireEditableCheck(rc);
        if ((rc.getCustomerClearedAt() != null) == cleared) return toResponse(rc);
        requireCurrentCheck(rc, revision);
        var before = toResponse(rc);
        rc.setCustomerClearedAt(cleared ? Instant.now() : null);
        if (!cleared) {
            rc.setClearanceRevision(rc.getClearanceRevision() + 1);
            if (Boolean.TRUE.equals(rc.getWarehouseEmptyVerified())) {
                rc.setWarehouseEmptyVerified(null);
                rc.setWarehouseEmptyVerifiedAt(null);
                rc.setWarehouseEmptyVerifiedBy(null);
            }
        }
        var after = toResponse(rc);
        String title = cleared ? "Khách đã xác nhận dọn kho" : "Khách đã thu hồi xác nhận dọn kho";
        notificationService.createFacilityOperationsNotifications(rc.getFacility().getId(), NotificationType.RETURN,
            title, title + " tại gian " + rc.getStorageUnit().getCode() + ".", id);
        auditLogService.recordMutation(cleared ? "CUSTOMER_RETURN_CLEARED" : "CUSTOMER_RETURN_CLEARANCE_REVOKED",
            "return", id, rc.getFacility().getId(), before, after);
        return after;
    }

    @Transactional
    public ReturnCaseResponse savePropertyCheck(ActorPrincipal actor, UUID id, ReturnPropertyCheckRequest request) {
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        var rc = returnCaseRepository.findById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ trả kho."));
        facilityScopeService.assertCanOperate(actor, rc.getFacility().getId());
        lockRecoveryReturn(rc);
        requireEditableCheck(rc);
        requireReturnHandoverAccess(rc, actor.userId(), Instant.now());
        requireCurrentCheck(rc, request.clearanceRevision());
        if (request.unitVacated() == null)
            throw ApiExceptions.validation("Vui lòng chọn tình trạng tài sản trong gian kho.", null);
        if (!request.unitVacated() && (request.staffNotes() == null || request.staffNotes().isBlank()))
            throw ApiExceptions.validation("Vui lòng mô tả tài sản còn lại.", null);
        validateInspectionPhotos(rc, request.evidencePhotos(), false);
        var before = toResponse(rc);
        recordPropertyCheck(rc, request.unitVacated(), actor.userId());
        rc.setClearanceRevision(rc.getClearanceRevision() + 1);
        rc.setInspectionNotes(request.staffNotes());
        if (request.evidencePhotos() != null) {
            try {
                rc.setEvidencePhotosJson(objectMapper.writeValueAsString(request.evidencePhotos()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Không thể lưu ảnh kiểm tra.", e);
            }
        }
        if (!request.unitVacated())
            notificationService.createNotification(rc.getCustomer().getId(), NotificationType.RETURN,
                "Gian kho vẫn còn tài sản", "Vui lòng dọn hết tài sản tại gian " + rc.getStorageUnit().getCode()
                    + ". Ghi chú của nhân viên: " + request.staffNotes(), id);
        var after = toResponse(rc);
        auditLogService.recordMutation("RETURN_PROPERTY_CHECK", "return", id, rc.getFacility().getId(), before, after);
        return after;
    }

    private void captureReturnArrival(ActorPrincipal actor, ReturnCase rc, UUID evidenceId, Instant now) {
        if (!ReturnAttendancePolicy.canRecordArrivalAt(rc, now))
            throw ApiExceptions.conflict("Đã hết hạn tiếp nhận trả kho. Không thể ghi nhận khách đến đúng hạn.");
        if (evidenceId == null)
            throw ApiExceptions.validation("Cần ảnh hiện trường để xác minh khách đến trả kho.", null);
        validateInspectionPhotos(rc, List.of(evidenceId.toString()), true);
        FileAsset evidence = settlementEntityManager.find(FileAsset.class, evidenceId);
        if (evidence.getUploadedBy() == null || !actor.userId().equals(evidence.getUploadedBy().getId()))
            throw ApiExceptions.validation("Vui lòng tải ảnh tiếp nhận từ tài khoản của bạn.", null);
        rc.setReturnArrivedAt(now);
        rc.setReturnArrivalEvidenceId(evidenceId);
        auditLogService.recordMutation(ReturnAttendancePolicy.isScheduledRentalReturn(rc)
            ? "RETURN_ARRIVED" : "MISMATCH_RETURN_ARRIVED", "return", rc.getId(), rc.getFacility().getId(),
            null, Map.of("staffId", actor.userId(), "arrivedAt", now, "evidenceId", evidenceId));
    }

    @Transactional
    public ReturnCaseResponse verifyReturnIdentity(ActorPrincipal actor, UUID id, UUID attemptId, String documentNumber) {
        return verifyReturnIdentity(actor, id, attemptId, documentNumber, null);
    }

    @Transactional
    public ReturnCaseResponse verifyReturnIdentity(ActorPrincipal actor, UUID id, UUID attemptId, String documentNumber,
                                                   Instant reverificationStartedAt) {
        return verifyReturnIdentity(actor, id, attemptId, documentNumber, reverificationStartedAt, null);
    }

    @Transactional
    public ReturnCaseResponse verifyReturnIdentity(ActorPrincipal actor, UUID id, UUID attemptId, String documentNumber,
                                                   Instant reverificationStartedAt, UUID arrivalEvidenceId) {
        ReturnCase rc = lockReturnAttendance(actor, id, false);
        if (!java.util.Objects.equals(rc.getReturnReverificationStartedAt(), reverificationStartedAt))
            throw ApiExceptions.conflict("Phiên xác minh đã thay đổi. Vui lòng tải lại hồ sơ.");
        if (rc.getReturnReverificationStaffId() != null && !actor.userId().equals(rc.getReturnReverificationStaffId()))
            throw ApiExceptions.forbidden("Chỉ nhân viên được phân công mới có thể xác minh lại.");
        if (attemptId.equals(rc.getReturnFirstAttemptId()) || attemptId.equals(rc.getReturnSecondAttemptId())
            || rc.getReturnIdentityVerifiedAt() != null) return toResponse(rc);
        Instant expires = ReturnAttendancePolicy.expiresAt(rc);
        Instant now = Instant.now();
        if ((expires != null && !now.isBefore(expires)) || rc.getReturnSecondAttemptId() != null
            || rc.getReturnArrivalReviewedAt() != null)
            throw ApiExceptions.conflict("Phiên xác minh chưa mở hoặc đã kết thúc. Vui lòng kiểm tra trạng thái đối soát.");
        var reservation = rc.getRental().getReservation();
        String expected = normalizeReturnDocument(reservation == null ? null : reservation.getIdentityDocumentNumber());
        if (expected.isEmpty()) throw ApiExceptions.conflict("Hồ sơ thiếu giấy tờ đối chiếu. Không ghi nhận lần thử.");
        String actual = normalizeReturnDocument(documentNumber);
        if (!com.storagehub.validation.IdentityDocumentPolicy.isValid(actual)
            && !actual.matches("(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6,20}"))
            throw ApiExceptions.validation("Định dạng giấy tờ không hợp lệ. Không ghi nhận lần thử.", null);
        if (rc.getReturnArrivedAt() == null) captureReturnArrival(actor, rc, arrivalEvidenceId, now);
        boolean matched = expected.equals(actual);
        if (rc.getReturnFirstAttemptId() == null) rc.setReturnFirstAttemptId(attemptId);
        else {
            rc.setReturnSecondAttemptId(attemptId);
            rc.setReturnSecondAttemptAt(now);
        }
        if (matched) rc.setReturnIdentityVerifiedAt(now);
        auditLogService.recordMutation(ReturnAttendancePolicy.isScheduledRentalReturn(rc)
            ? "RETURN_IDENTITY_ATTEMPT" : "MISMATCH_RETURN_IDENTITY_ATTEMPT", "return", id, rc.getFacility().getId(),
            null, Map.of("attemptId", attemptId, "staffId", actor.userId(), "matched", matched, "attemptedAt", now));
        return toResponse(rc);
    }

    @Transactional
    public ReturnCaseResponse reopenReturnVerification(ActorPrincipal actor, UUID id, UUID staffId, String reason) {
        ReturnCase rc = lockReturnAttendance(actor, id, true);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant reviewStart = ReturnAttendancePolicy.reviewStartsAt(rc);
        if (reviewStart == null || now.isBefore(reviewStart) || rc.getReturnIdentityVerifiedAt() != null
            || rc.getReturnArrivalEvidenceId() == null || rc.getReturnReverificationStartedAt() != null) {
            throw ApiExceptions.conflict("Chỉ được mở lại một lần cho hồ sơ đang chờ đối soát và có ảnh tiếp nhận khách.");
        }
        requireEligibleReturnStaff(rc, staffId);
        var before = toResponse(rc).attendance();
        rc.setReturnReverificationStartedAt(now);
        rc.setReturnReverificationStaffId(staffId);
        rc.setReturnFirstAttemptId(null);
        rc.setReturnSecondAttemptId(null);
        rc.setReturnSecondAttemptAt(null);
        auditLogService.recordMutation("RETURN_IDENTITY_REOPENED", "return", id, rc.getFacility().getId(), before,
            Map.of("managerId", actor.userId(), "staffId", staffId, "startedAt", now, "reason", reason.strip()));
        notificationService.createNotification(staffId, NotificationType.RETURN, "Cần xác minh lại khi trả kho",
            "Mở hồ sơ trả kho và đối chiếu giấy tờ gốc trong vòng 30 phút.", id);
        return toResponse(rc);
    }

    private static String normalizeReturnDocument(String value) {
        return value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
    }

    @Transactional
    public ReturnCaseResponse reviewReturnArrival(ActorPrincipal actor, UUID id, boolean accepted, String reason) {
        return reviewReturnArrival(actor, id, accepted, reason, null, null);
    }

    @Transactional
    public ReturnCaseResponse reviewReturnArrival(ActorPrincipal actor, UUID id, boolean accepted, String reason, UUID staffId, Instant appointmentAt) {
        ReturnCase rc = lockReturnAttendance(actor, id, true);
        Instant now = Instant.now();
        Instant reviewStart = ReturnAttendancePolicy.reviewStartsAt(rc);
        if (reviewStart == null || now.isBefore(reviewStart))
            throw ApiExceptions.conflict("Hồ sơ chưa đến lúc đối soát hoặc đã có kết quả.");
        if (accepted && rc.getReturnIdentityVerifiedAt() == null)
            throw ApiExceptions.conflict("Chỉ có thể tiếp tục bàn giao khi danh tính đã được xác minh.");
        if (accepted && recoveryCalendar != null) {
            requireEligibleReturnStaff(rc, staffId);
            if (appointmentAt != null && appointmentAt.isBefore(now))
                throw ApiExceptions.validation("Lịch bàn giao phải nằm trong tương lai.", null);
            rc.setPostReviewHandoverStaffId(staffId);
            rc.setPostReviewHandoverAppointmentAt(appointmentAt);
            rc.setPostReviewHandoverDueAt(recoveryCalendar.current().afterWorkingHours(appointmentAt == null ? now : appointmentAt, 4));
            notificationService.createNotification(staffId, NotificationType.RETURN, "Cần bàn giao sau đối soát",
                "Mở hồ sơ trả kho để hoàn tất bàn giao trước hạn.", rc.getId());
            if (appointmentAt != null) {
                notificationService.createNotification(rc.getCustomer().getId(), NotificationType.RETURN, "Đã cập nhật lịch bàn giao trả kho",
                    "Mở hồ sơ trả kho để xem lịch bàn giao sau đối soát.", rc.getId());
            }
        } else {
            rc.setPostReviewHandoverStaffId(null);
            rc.setPostReviewHandoverAppointmentAt(null);
            rc.setPostReviewHandoverDueAt(null);
        }
        rc.setReturnArrivalReviewedAt(now);
        rc.setReturnArrivalReviewAccepted(accepted);
        var decision = new java.util.LinkedHashMap<String, Object>(Map.of("managerId", actor.userId(),
            "accepted", accepted, "reason", reason.strip(), "reviewedAt", now));
        if (rc.getPostReviewHandoverAppointmentAt() != null) decision.put("appointmentAt", rc.getPostReviewHandoverAppointmentAt());
        auditLogService.recordMutation(ReturnAttendancePolicy.isScheduledRentalReturn(rc)
            ? "RETURN_ARRIVAL_REVIEWED" : "MISMATCH_RETURN_ARRIVAL_REVIEWED", "return", id, rc.getFacility().getId(),
            null, decision);
        return toResponse(rc);
    }

    private void requireEligibleReturnStaff(ReturnCase rc, UUID staffId) {
        if (staffId == null || userRepository.findEligibleStaff(UserStatus.ACTIVE, RoleCode.STAFF, rc.getFacility().getId(),
            List.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)).stream().noneMatch(staff -> staffId.equals(staff.getId())))
            throw ApiExceptions.validation("Cần chọn nhân viên phụ trách tại cơ sở.", null);
    }

    private void requireReturnHandoverAccess(ReturnCase rc, UUID staffId, Instant now) {
        Instant appointment = rc.getPostReviewHandoverAppointmentAt();
        Instant due = rc.getPostReviewHandoverDueAt();
        UUID assigned = rc.getReturnArrivalReviewAccepted() == null
            ? rc.getReturnReverificationStaffId() : rc.getPostReviewHandoverStaffId();
        if (assigned != null && !staffId.equals(assigned))
            throw ApiExceptions.forbidden("Chỉ nhân viên được phân công mới được nghiệm thu.");
        if (appointment != null && now.isBefore(appointment))
            throw ApiExceptions.conflict("Chưa đến lịch bàn giao sau đối soát.");
        if (due != null && !now.isBefore(due))
            throw ApiExceptions.conflict("Đã hết hạn bàn giao. Hồ sơ cần Quản lý đối soát lại.");
    }

    private ReturnCase lockReturnAttendance(ActorPrincipal actor, UUID id, boolean review) {
        if (!actor.hasRole(review ? RoleCode.MANAGER : RoleCode.STAFF))
            throw ApiExceptions.forbidden("Vai trò không có quyền xử lý bước bàn giao này.");
        authorizationService.require(actor, review ? SystemPermission.MANAGE_RENTALS : SystemPermission.PROCESS_RETURNS);
        ReturnCase rc = returnCaseRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ trả kho."));
        if (review) facilityScopeService.assertCanManage(actor, rc.getFacility().getId());
        else facilityScopeService.assertCanOperate(actor, rc.getFacility().getId());
        lockRecoveryReturn(rc);
        if (!ReturnAttendancePolicy.applies(rc) || !ReturnAttendancePolicy.isOpen(rc) || isRecovery(rc)
            || (ReturnAttendancePolicy.isDeadlineMismatchReturn(rc) && rc.getRental().getInventoryEscalatedAt() != null))
            throw ApiExceptions.conflict("Hồ sơ không còn ở bước tiếp nhận trả kho.");
        return rc;
    }

    private void requireEditableCheck(ReturnCase rc) {
        if ((rc.getStatus() != ReturnCaseStatus.requested && rc.getStatus() != ReturnCaseStatus.scheduled)
            || rc.getInspectedAt() != null || rc.getNoShowAt() != null || isRecovery(rc))
            throw ApiExceptions.conflict("Hồ sơ đã qua giai đoạn cập nhật tình trạng dọn kho.");
    }

    private void requireCurrentCheck(ReturnCase rc, long revision) {
        if (rc.getClearanceRevision() != revision)
            throw ApiExceptions.conflict("Tình trạng dọn kho đã thay đổi. Vui lòng tải lại và kiểm tra thực tế gian kho.");
    }

    private void recordPropertyCheck(ReturnCase rc, boolean empty, UUID staffId) {
        rc.setWarehouseEmptyVerified(empty);
        rc.setWarehouseEmptyVerifiedAt(Instant.now());
        rc.setWarehouseEmptyVerifiedBy(staffId);
    }

    private void validateInspectionPhotos(ReturnCase rc, List<String> photos, boolean required) {
        if (photos == null || photos.isEmpty()) {
            if (required) throw ApiExceptions.validation("Cần ít nhất một ảnh hiện trường để hoàn tất nghiệm thu.", null);
            return;
        }
        boolean hasImage = false;
        for (String photo : photos) {
            UUID assetId;
            try {
                assetId = UUID.fromString(photo);
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw ApiExceptions.validation("Mã ảnh nghiệm thu không hợp lệ.", null);
            }
            FileAsset asset = settlementEntityManager.find(FileAsset.class, assetId);
            if (asset == null || asset.getStatus() != FileAssetStatus.ACTIVE || !"RETURN_CASE".equals(asset.getEntityType())
                || !rc.getId().equals(asset.getEntityId()))
                throw ApiExceptions.validation("Ảnh nghiệm thu phải thuộc hồ sơ trả kho đang xử lý.", null);
            hasImage |= asset.getContentType() != null && asset.getContentType().startsWith("image/");
        }
        if (required && !hasImage) throw ApiExceptions.validation("Cần ít nhất một ảnh hiện trường để hoàn tất nghiệm thu.", null);
    }

    private void validateRemovalDate(Rental rental, LocalDate date) {
        LocalDate first = InventoryTimelinePolicy.removalEffectiveAt(rental.getInventoryRemovalPublishedAt())
            .atZone(BusinessTime.ZONE).toLocalDate();
        LocalDate last = rental.getInventoryRemovalDeadlineAt().atZone(BusinessTime.ZONE).toLocalDate().minusDays(1);
        if (date == null || date.isBefore(first) || date.isAfter(last))
            throw ApiExceptions.validation("Ngày hẹn trả kho phải từ " + first + " đến hết " + last + ".", null);
    }

    static boolean inSetupReturnWindow(Rental rental) {
        return rental.getStatus() == RentalStatus.setup
            || rental.getSetupCompletionDeadlineAt() != null && rental.getInventoryVerifiedAt() == null
            || rental.getSetupStartedAt() != null && rental.getSetupEndsAt() != null && Instant.now().isBefore(rental.getSetupEndsAt());
    }

    private void classifyScheduledReturn(ReturnCase record) {
        Rental rental = record.getRental();
        LocalDate lastRentalDay = rentalPeriods == null ? rental.getContractEndDate()
            : rentalPeriods.read(rental).map(RentalPeriod::lastPermittedDate).orElse(rental.getContractEndDate());
        boolean normalReturn = lastRentalDay != null && !record.getScheduledDate().isBefore(lastRentalDay);
        record.setReturnType(normalReturn ? ReturnType.NORMAL : ReturnType.EARLY_RETURN);
        record.setActiveReturnRepricingRequired(!normalReturn);
        record.setSettlementMode(!normalReturn && !hasPricingTiers(rental) ? SettlementMode.MANUAL_REVIEW : SettlementMode.AUTO);
    }

    private Instant noShowRecoveryEligibleAt(LocalDate scheduledDate) {
        long grace = Math.max(0, noShowGraceHours);
        return scheduledDate.plusDays(1)
            .atStartOfDay(BusinessTime.ZONE)
            .toInstant()
            .plus(grace, ChronoUnit.HOURS);
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
        if (returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH
            || rental != null && rental.getSetupCompletionDeadlineAt() != null) {
            throw ApiExceptions.conflict("Hồ sơ kiểm kê sai lệch phải được nghiệm thu và gửi khách xác nhận quyết toán.");
        }
        if (rental == null || !rental.isSetupExitReviewRequired()) {
            throw ApiExceptions.conflict("Hồ sơ trả kho này không chờ Business quyết định xử lý trong thời gian chuẩn bị");
        }
        if (rental.getSetupStartedAt() == null || returnCase.getSetupReturnedAt() == null) {
            throw ApiExceptions.conflict("Staff phải xác nhận kho đã trống trước khi quyết định tiền trả trong thời gian chuẩn bị");
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
        BigDecimal securityDeductions = zeroIfNull(returnCase.getDamageFee())
            .add(zeroIfNull(returnCase.getCleaningFee()))
            .add(zeroIfNull(returnCase.getLostItemFee()))
            .add(zeroIfNull(returnCase.getOverdueFee()))
            .add(zeroIfNull(returnCase.getOutstandingFee()))
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal approvedRefund = request.approvedRefundAmount().setScale(2, RoundingMode.HALF_UP);
        if (approvedRefund.compareTo(deposit) > 0) {
            throw ApiExceptions.conflict("Khoản hoàn được duyệt không thể vượt quá tiền cọc đảm bảo thuê đã xác minh");
        }
        BigDecimal maximumDepositRefund = deposit.subtract(securityDeductions).max(BigDecimal.ZERO);
        if (approvedRefund.compareTo(maximumDepositRefund) > 0) {
            throw ApiExceptions.conflict("Khoản hoàn cọc không thể vượt quá tiền cọc sau khi trừ các khoản hư hỏng và công nợ đã có chứng cứ");
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
        returnCase.setManagerResolutionNote("Business quyết định xử lý trả kho trong thời gian chuẩn bị: " + request.resolutionNote().trim());
        returnCase.setEarlyTerminationCharge(rentalCharge);
        returnCase.setRentalRefundAmount(approvedRentalRefund);
        returnCase.setTotalDeductions(retained.add(rentalCharge).setScale(2, RoundingMode.HALF_UP));
        returnCase.setNetRefundAmount(approvedRefund.add(approvedRentalRefund).setScale(2, RoundingMode.HALF_UP));
        BigDecimal amountDue = securityDeductions.subtract(deposit).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        returnCase.setAmountDueFromCustomer(amountDue);
        boolean refundPending = approvedRefund.signum() > 0 || approvedRentalRefund.signum() > 0;
        // The decision fixes the statement amounts. The customer must always see and confirm
        // this statement before any money moves, so the case can never jump straight to a
        // refund, a collection or completion from this path.
        if (returnCase.isCustomerConfirmed()) {
            throw ApiExceptions.conflict("Bảng kê đã được khách xác nhận. Không thể thay đổi số tiền đã chốt.");
        }
        returnCase.setCustomerConfirmed(false);
        returnCase.setCustomerConfirmedAt(null);
        returnCase.setCustomerDecision(null);
        returnCase.setCustomerDecisionNote(null);
        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);

        rental.setSetupExitReviewRequired(false);
        rentalRepository.save(rental);

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
            "Business đã quyết định xử lý yêu cầu trả kho trong thời gian chuẩn bị",
            "Bảng kê quyết toán cho gian " + saved.getStorageUnit().getCode()
                + " đã được lập (dự kiến hoàn " + approvedRefund.add(approvedRentalRefund)
                    .setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " đ). Vui lòng kiểm tra và xác nhận bảng kê. Hệ thống không tự đồng ý thay bạn.",
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
        var settlement = calculateSettlement(returnCase, securityDeductions, charge, refund);

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
        returnCase.setTotalDeductions(settlement.deductions());
        returnCase.setNetRefundAmount(settlement.refund());
        returnCase.setAmountDueFromCustomer(settlement.due());
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
        return performInspection(actor, returnId, request, 0);
    }

    @Transactional
    public ReturnCaseResponse performInspection(ActorPrincipal actor, UUID returnId,
        ReturnInspectionRequest request, long clearanceRevision) {
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));

        facilityScopeService.assertCanOperate(actor, returnCase.getFacility().getId());
        lockRecoveryReturn(returnCase);

        if (returnCase.getStatus() != ReturnCaseStatus.requested
            && returnCase.getStatus() != ReturnCaseStatus.scheduled
            && returnCase.getStatus() != ReturnCaseStatus.recovery_required)
            throw ApiExceptions.conflict("Return is not in a state awaiting inspection");
        if (violationRecovery != null && !isRecovery(returnCase) && violationRecovery.blocksLegacyReturn(returnCase.getRental().getId()))
            throw ApiExceptions.conflict("Mở hồ sơ Thu hồi kho để xử lý theo nhiệm vụ đã được phê duyệt.");
        Instant now = Instant.now();
        requireReturnHandoverAccess(returnCase, actor.userId(), now);
        var lockResult = request.lockInspectionResult() == null
            ? LockInspectionResult.UNVERIFIED : request.lockInspectionResult();
        if (lockResult == LockInspectionResult.RETURNED_USABLE
            && (!request.returnedLock() || !request.returnedKey())) {
            throw ApiExceptions.validation("Cần xác nhận đã nhận lại ổ khóa và đủ chìa khóa để cấp lại mã ổ khóa.", null);
        }
        boolean noShowRecovery = returnCase.getStatus() == ReturnCaseStatus.recovery_required;
        boolean recoveredBeforeInspection = ReturnAttendancePolicy.isDeadlineMismatchReturn(returnCase)
            && returnCase.getReadyForInspectionAt() != null && returnCase.getRental().getActualReturnedAt() != null;
        if (!noShowRecovery && !isRecovery(returnCase) && ReturnAttendancePolicy.applies(returnCase)
            && !recoveredBeforeInspection && !ReturnAttendancePolicy.canInspect(returnCase, now)) {
            throw ApiExceptions.conflict("Cần phiên bàn giao đã xác minh danh tính còn hiệu lực hoặc kết quả đối soát cho phép tiếp tục.");
        }
        boolean ordinaryInspection = !noShowRecovery && !isRecovery(returnCase);
        if (ordinaryInspection) {
            requireCurrentCheck(returnCase, clearanceRevision);
            if (!request.unitVacated() || request.damageClassification() == com.storagehub.domain.model.DamageClassification.abandoned_goods)
                throw ApiExceptions.conflict("Cần xác minh gian kho trống trước khi hoàn tất nghiệm thu.");
            validateInspectionPhotos(returnCase, request.evidencePhotos(), true);
            var equipment = resolveEquipment(returnCase);
            if (equipment == null || equipment.palletCount() == null)
                throw ApiExceptions.validation("Cần đối soát số tấm kê đã bàn giao trước khi nghiệm thu.", null);
            recordPropertyCheck(returnCase, true, actor.userId());
        }
        MaintenanceTask noShowTask = noShowRecovery ? requireNoShowRecoveryTask(returnCase) : null;
        if (noShowRecovery) {
            if (!request.unitVacated()
                || request.damageClassification() == com.storagehub.domain.model.DamageClassification.abandoned_goods) {
                throw ApiExceptions.conflict("Cần đưa toàn bộ hàng ra khỏi gian và ghi nhận khu vực lưu tạm trước khi nghiệm thu khách không đến.");
            }
            validateInspectionPhotos(returnCase, request.evidencePhotos(), true);
            if (request.staffNotes() == null || request.staffNotes().isBlank())
                throw ApiExceptions.validation("Cần ghi danh mục hàng, tình trạng niêm phong và người nhận bàn giao.", null);
            if (request.recoveryHandoverLocation() == null || request.recoveryHandoverLocation().isBlank()) {
                throw ApiExceptions.validation("Cần ghi nhận khu vực lưu tạm và người nhận bàn giao trước khi hoàn tất thu hồi hàng.", null);
            }
            if (noShowTask.getStatus() == MaintenanceTaskStatus.cancelled) {
                throw ApiExceptions.conflict("Nhiệm vụ thu hồi hàng đã bị hủy. Cần tạo lại nhiệm vụ trước khi nghiệm thu.");
            }
            noShowTask.setRecoveryWitness(requireRecoveryWitness(
                request.recoveryWitnessStaffId(), returnCase.getFacility().getId(), actor.userId()
            ));
            recordPropertyCheck(returnCase, true, actor.userId());
        }
        if (!noShowRecovery && !request.customerPresent() && !returnCase.isSetupExitRequested()) {
            throw ApiExceptions.conflict("Cần xác nhận khách hàng có mặt tại thời điểm nghiệm thu trả kho.");
        }
        if (!returnCase.isSetupExitRequested() && returnCase.getRequestedBy() != null
            && returnCase.getCustomer() != null
            && returnCase.getCustomer().getId().equals(returnCase.getRequestedBy().getId())) {
            classifyScheduledReturn(returnCase);
        }
        if (returnCase.isSetupExitRequested()) {
            if (!request.unitVacated() || request.damageClassification() == com.storagehub.domain.model.DamageClassification.abandoned_goods)
                throw ApiExceptions.conflict("Chỉ nghiệm thu trả kho trong thời gian chuẩn bị sau khi khách đã đưa hết hàng ra và bàn giao kho trống.");
            Instant returnedAt = returnCase.getRental().getActualReturnedAt();
            if (returnedAt == null && noShowRecovery && noShowTask.getStatus() == MaintenanceTaskStatus.completed)
                returnedAt = noShowTask.getCompletedAt();
            if (returnedAt == null) returnedAt = returnCase.getReadyForInspectionAt();
            returnCase.setSetupReturnedAt((returnedAt == null ? Instant.now() : returnedAt)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            if (calculatedDamageFee(request).signum() > 0 || request.cleaningFee().signum() > 0 || request.lostItemFee().signum() > 0) {
                if (request.staffNotes() == null || request.staffNotes().isBlank() || request.evidencePhotos() == null || request.evidencePhotos().isEmpty())
                    throw ApiExceptions.validation("Phí phát sinh trong thời gian chuẩn bị cần ghi chú và bằng chứng kiểm kê.", null);
                if (!ordinaryInspection) validateInspectionPhotos(returnCase, request.evidencePhotos(), false);
            }
        }

        User staff = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Staff user not found"));

        BigDecimal safeDamage = calculatedDamageFee(request);
        BigDecimal safeCleaning = safeFee(request.cleaningFee());
        BigDecimal safeLostItem = safeFee(request.lostItemFee());
        BigDecimal safeOverdue = safeFee(request.overdueFee());
        Instant inspectionAt=Instant.now();
        returnCase.setInspectedAt(inspectionAt);
        var verifiedFinance=captureFinance(returnCase);
        BigDecimal safeOutstanding = verifiedFinance.debt();
        safeOverdue=settlementOverdueFee(returnCase,safeOverdue,verifiedFinance);
        if (returnCase.isSetupEarlyExit()) safeOverdue = BigDecimal.ZERO;

        BigDecimal deductions = safeDamage
            .add(safeCleaning)
            .add(safeLostItem)
            .add(safeOverdue)
            .add(safeOutstanding)
            .setScale(2, RoundingMode.HALF_UP);

        var activePricing = activeReturnCalculation(returnCase);
        boolean legacyPricingReview = returnCase.isActiveReturnRepricingRequired() && activePricing == null;
        boolean activeRepricing = activePricing != null && activePricing.recalculationRequired();
        if (returnCase.getRental().getStatus() == RentalStatus.setup
            && returnCase.getReturnType() != ReturnType.INVENTORY_MISMATCH) {
            boolean goodsMovedIn = returnCase.getRental().getMoveInCompletedAt() != null
                || returnCase.getRental().getInventoryVerifiedAt() != null;
            returnCase.setReturnType(goodsMovedIn ? ReturnType.RETURN_IN_SETUP : ReturnType.CANCELLED_IN_SETUP);
            returnCase.setSettlementMode(SettlementMode.MANUAL_REVIEW);
        } else if (!legacyPricingReview && returnCase.getReturnType() == null) {
            returnCase.setReturnType(activeRepricing ? ReturnType.EARLY_RETURN : ReturnType.NORMAL);
        }
        boolean inventoryMismatchExit = returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && returnCase.isSetupExitRequested();
        var mismatchPricing = inventoryMismatchExit ? setupExitCalculation(returnCase) : null;
        boolean calendarSetup = !inventoryMismatchExit && returnCase.isSetupExitRequested()
            && returnCase.getRental().getSetupCompletionDeadlineAt() != null;
        var calendarPricing = calendarSetup ? setupExitCalculation(returnCase) : null;
        if (calendarSetup && calendarPricing == null)
            throw ApiExceptions.conflict("Thiếu bảng tính ngày sử dụng của hồ sơ trả setup.");
        if (inventoryMismatchExit && mismatchPricing == null)
            throw ApiExceptions.conflict("Thiếu bảng tính thời gian sử dụng của hồ sơ kiểm kê sai lệch.");
        if (inventoryMismatchExit && mismatchPricing != null) {
            var snapshot = reservationPricingSnapshot(returnCase);
            if (returnCase.getRentalRefundCredit() == null || returnCase.getRentalRefundCredit().signum() == 0) {
                returnCase.setRentalRefundCredit(snapshot == null ? BigDecimal.ZERO : zeroIfNull(snapshot.getNetRentalAmount()));
            }
            returnCase.setEarlyTerminationFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal earlyTerminationCharge = calendarPricing != null ? calendarPricing.totalRentalCharge() : inventoryMismatchExit && mismatchPricing != null
            ? mismatchPricing.totalRentalCharge()
            : returnCase.isSetupEarlyExit()
                ? zeroIfNull(returnCase.getEarlyTerminationFee())
                : (!legacyPricingReview && activeRepricing ? activePricing.totalRentalCharge() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        BigDecimal rentalRefund = inventoryMismatchExit
            ? zeroIfNull(returnCase.getRentalRefundCredit())
            : returnCase.isSetupEarlyExit()
                ? zeroIfNull(returnCase.getRentalRefundCredit())
                : (!legacyPricingReview && activeRepricing ? activePricing.rentalRefundAmount() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        var settlement = calculateSettlement(returnCase, deductions, earlyTerminationCharge, rentalRefund);
        requireSettlementLimit(settlement.deductions());

        returnCase.setInspectedBy(staff);
        returnCase.setInspectedAt(inspectionAt);
        returnCase.setInventoryMatch(request.inventoryMatch());
        returnCase.setDamageClassification(request.damageClassification());
        String damageBreakdown = ReturnDamagePricing.describe(request.damageItems());
        String inspectionNotes = request.staffNotes();
        if (!damageBreakdown.isBlank()) {
            inspectionNotes = (inspectionNotes == null ? "" : inspectionNotes.trim());
            inspectionNotes = (inspectionNotes.isBlank() ? "" : inspectionNotes + "\n\n")
                + "Bảng giá hư hỏng tự động: " + damageBreakdown;
        }
        returnCase.setInspectionNotes(inspectionNotes);
        returnCase.setReturnedKey(request.returnedKey());
        returnCase.setReturnedCard(request.returnedCard());
        returnCase.setReturnedLock(request.returnedLock());
        returnCase.setLockInspectionResult(lockResult);
        returnCase.setProposedUnitStatus(request.proposedUnitStatus() != null ? request.proposedUnitStatus() : StorageUnitStatus.available);

        returnCase.setDamageFee(safeDamage);
        returnCase.setCleaningFee(safeCleaning);
        returnCase.setLostItemFee(safeLostItem);
        returnCase.setOverdueFee(safeOverdue);
        returnCase.setOutstandingFee(safeOutstanding);
        returnCase.setEarlyTerminationCharge(earlyTerminationCharge);
        returnCase.setRentalRefundAmount(rentalRefund);
        returnCase.setTotalDeductions(settlement.deductions());
        returnCase.setNetRefundAmount(settlement.refund());
        returnCase.setAmountDueFromCustomer(settlement.due());
        returnCase.setLegacyPricingReviewRequired(legacyPricingReview);
        if (legacyPricingReview) {
            returnCase.setSettlementMode(SettlementMode.MANUAL_REVIEW);
        }

        if (accessCredentialService != null) {
            accessCredentialService.revokeForReturnInspection(
                actor,
                returnCase.getRental(),
                "Thu hồi quyền truy cập trong nghiệm thu trả kho",
                lockResult
            );
        }

        if (request.evidencePhotos() != null && !request.evidencePhotos().isEmpty()) {
            try {
                returnCase.setEvidencePhotosJson(objectMapper.writeValueAsString(request.evidencePhotos()));
            } catch (Exception exception) {
                returnCase.setEvidencePhotosJson("[]");
            }
        }

        if (noShowRecovery && noShowTask.getStatus() != MaintenanceTaskStatus.completed) {
            noShowTask.setStatus(MaintenanceTaskStatus.completed);
            noShowTask.setCompletedAt(inspectionAt);
            noShowTask.setResultReport(request.staffNotes().trim());
            noShowTask.setRecoveryHandoverLocation(request.recoveryHandoverLocation().trim());
            try {
                noShowTask.setEvidencePhotosJson(objectMapper.writeValueAsString(request.evidencePhotos()));
            } catch (Exception ignored) {
                noShowTask.setEvidencePhotosJson("[]");
            }
            maintenanceTaskRepository.save(noShowTask);
        }

        returnCase.setStatus(legacyPricingReview
            ? ReturnCaseStatus.awaiting_business_review
            : ReturnCaseStatus.awaiting_customer_confirmation);
        if (!legacyPricingReview) {
            publishMismatchStatement(returnCase, inspectionAt);
            if (inventoryMismatchExit) returnCase.setSettlementLedgerRevision(verifiedFinance.ledgerRevision());
        }
        ReturnCase saved = returnCaseRepository.save(returnCase);

        Rental rental = saved.getRental();
        rental.setStatus(RentalStatus.return_inspection);
        if (saved.getReturnType() == ReturnType.INVENTORY_MISMATCH) {
            rental.setInventoryPending(false);
            completeInventoryTasksAfterMismatch(saved, inspectionAt);
            if (request.unitVacated()) {
                StorageUnit unit = saved.getStorageUnit();
                if (mismatchUnitRelease != null) {
                    unit = storageUnitRepository.findByIdForUpdate(unit.getId())
                        .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy gian kho."));
                    rental.setActualReturnedAt(saved.getSetupReturnedAt() == null ? inspectionAt : saved.getSetupReturnedAt());
                    recordPropertyCheck(saved, true, actor.userId());
                    mismatchUnitRelease.open(saved, unit, staff, inspectionAt);
                }
                unit.setStatus(StorageUnitStatus.maintenance);
                storageUnitRepository.save(unit);
                saved.setProposedUnitStatus(StorageUnitStatus.maintenance);
            }
        }
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
    public ReturnCaseResponse managerReviewInventoryMismatch(
        ActorPrincipal actor,
        UUID returnId,
        ManagerInventoryMismatchDecisionRequest request
    ) {
        if (actor == null || (!actor.hasRole(RoleCode.MANAGER) && !actor.hasRole(RoleCode.ADMIN))) {
            throw ApiExceptions.forbidden("Chỉ Manager được duyệt quyết toán kiểm kê sai lệch.");
        }
        authorizationService.require(actor, SystemPermission.MANAGE_RENTALS);
        authorizationService.require(actor, SystemPermission.APPROVE_REFUNDS);
        if (request == null || request.resolutionNote() == null || request.resolutionNote().isBlank()) {
            throw ApiExceptions.validation("Cần ghi nhận lý do Manager duyệt quyết toán.", null);
        }

        ReturnCase returnCase = returnCaseRepository.findById(returnId)
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));
        facilityScopeService.assertCanManage(actor, returnCase.getFacility().getId());
        lockRecoveryReturn(returnCase);
        if (returnCase.getStatus() != ReturnCaseStatus.awaiting_business_review
            || !(returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH
                || returnCase.isSetupExitRequested() && returnCase.getRental().getSetupCompletionDeadlineAt() != null)) {
            throw ApiExceptions.conflict("Hồ sơ kiểm kê sai lệch chưa ở trạng thái chờ Manager duyệt.");
        }
        var mismatchPricing = setupExitCalculation(returnCase);
        if (mismatchPricing == null) {
            throw ApiExceptions.conflict("Thiếu bảng tính ngày sử dụng của hồ sơ kiểm kê sai lệch.");
        }
        requireFinance().requireCurrent(returnCase);

        BigDecimal deductions = zeroIfNull(returnCase.getDamageFee())
            .add(zeroIfNull(returnCase.getCleaningFee()))
            .add(zeroIfNull(returnCase.getLostItemFee()))
            .add(zeroIfNull(returnCase.getOverdueFee()))
            .add(zeroIfNull(returnCase.getOutstandingFee()))
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal rentalCharge = mismatchPricing.totalRentalCharge();
        BigDecimal rentalRefund = zeroIfNull(returnCase.getRentalRefundCredit());
        var settlement = calculateSettlement(returnCase, deductions, rentalCharge, rentalRefund);
        requireSettlementLimit(settlement.deductions());

        User manager = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Manager user not found"));
        returnCase.setReviewedBy(manager);
        returnCase.setReviewedAt(Instant.now());
        returnCase.setManagerResolutionNote(request.resolutionNote().trim());
        if (returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH)
            returnCase.setEarlyTerminationFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        returnCase.setEarlyTerminationCharge(rentalCharge);
        returnCase.setRentalRefundAmount(rentalRefund);
        returnCase.setTotalDeductions(settlement.deductions());
        returnCase.setNetRefundAmount(settlement.refund());
        returnCase.setAmountDueFromCustomer(settlement.due());
        returnCase.setSettlementMode(SettlementMode.MANUAL_REVIEW);
        if (request.proposedUnitStatus() != null) {
            returnCase.setProposedUnitStatus(request.proposedUnitStatus());
        }
        returnCase.setCustomerConfirmed(false);
        returnCase.setCustomerConfirmedAt(null);
        returnCase.setCustomerDecision(null);
        returnCase.setCustomerDecisionNote(null);
        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
        publishMismatchStatement(returnCase, returnCase.getReviewedAt());

        ReturnCase saved = returnCaseRepository.save(returnCase);
        auditLogService.recordMutation(
            "INVENTORY_MISMATCH_RETURN_REVIEWED",
            "return",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );
        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.RETURN,
            "Manager đã duyệt quyết toán kiểm kê sai lệch",
            "Manager đã duyệt bảng tính tiền sử dụng kho và các khoản có bằng chứng. Vui lòng kiểm tra, đồng ý hoặc yêu cầu xem xét lại quyết toán.",
            saved.getId()
        );
        return toResponse(saved);
    }

    private void completeInventoryTasksAfterMismatch(ReturnCase returnCase, Instant completedAt) {
        if (maintenanceTaskRepository == null || returnCase.getRental() == null) return;
        maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(
            returnCase.getRental().getId(),
            MaintenanceTaskType.inventory_check,
            List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
        ).forEach(task -> {
            task.setStatus(MaintenanceTaskStatus.completed);
            task.setCompletedAt(completedAt);
            task.setResultReport("Đã kết thúc hồ sơ kiểm kê do hàng hóa không phù hợp với gian kho và hoàn tất quy trình trả kho.");
            maintenanceTaskRepository.save(task);
        });
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
        if (returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && !java.util.Objects.equals(request.settlementVersion(), returnCase.getSettlementVersion()))
            throw ApiExceptions.conflict("Bảng kê đã thay đổi. Vui lòng tải lại và kiểm tra phiên bản hiện tại.");
        if (returnCase.getStatus() == ReturnCaseStatus.completed) {
            return toResponse(returnCase);
        }
        if (returnCase.getStatus() != ReturnCaseStatus.awaiting_customer_confirmation)
            throw ApiExceptions.conflict("Return is not awaiting customer confirmation");
        if (request.decision() == CustomerDecision.accepted) {
            requireCurrentEarlyReturnAmounts(returnCase);
        }
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
                startMismatchPaymentDeadline(returnCase, returnCase.getCustomerConfirmedAt());
            } else if (returnCase.getNetRefundAmount() != null && returnCase.getNetRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
                markRefundPending(returnCase);
            } else {
                reconciliation.settle(returnCase, actor.userId(), "OFFSET:" + returnCase.getId(), false);
                returnCase.setStatus(ReturnCaseStatus.completed);
                returnCase.setCompletedAt(Instant.now());
                finalizeRentalAndUnit(returnCase);
            }
        }

        ReturnCase saved = returnCaseRepository.save(returnCase);

        if (request.decision() == CustomerDecision.disputed) {
            notificationService.createFacilityOperationsNotifications(
                saved.getFacility().getId(),
                NotificationType.RETURN,
                "Khách khiếu nại quyết toán trả kho",
                "Khách hàng " + saved.getCustomer().getFullName() + " đã khiếu nại quyết toán gian "
                    + saved.getStorageUnit().getCode() + ": " + request.note(),
                saved.getId()
            );
        } else if (saved.getStatus() == ReturnCaseStatus.refund_pending) {
            notificationService.createFacilityOperationsNotifications(
                saved.getFacility().getId(),
                NotificationType.RETURN,
                "Khách đã xác nhận - Cần hoàn cọc trả kho",
                "Khách hàng " + saved.getCustomer().getFullName() + " đã đồng ý quyết toán gian "
                    + saved.getStorageUnit().getCode() + ". Vui lòng hoàn tiền cọc cho khách.",
                saved.getId()
            );
        } else if (saved.getStatus() == ReturnCaseStatus.payment_due) {
            notificationService.createFacilityOperationsNotifications(
                saved.getFacility().getId(),
                NotificationType.RETURN,
                "Khách đã xác nhận - Chờ thu thêm tiền",
                "Khách hàng " + saved.getCustomer().getFullName() + " đã đồng ý quyết toán gian "
                    + saved.getStorageUnit().getCode() + ". Cần thu thêm tiền còn thiếu.",
                saved.getId()
            );
        }

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
    public ReturnCaseResponse staffConfirmSettlement(
        ActorPrincipal actor,
        UUID returnId,
        CustomerConfirmSettlementRequest request
    ) {
        authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        throw ApiExceptions.conflict(
            "Customer phải trực tiếp xác nhận hoặc gửi khiếu nại quyết toán. Nhân viên không được bỏ qua bước này."
        );
    }

    public int autoSettleExpiredReturns(ActorPrincipal actor) {
        if (actor == null || actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only operators can trigger return auto-settlement");
        }
        if (actor.hasRole(RoleCode.STAFF)) {
            authorizationService.require(actor, SystemPermission.PROCESS_RETURNS);
        } else if (actor.hasRole(RoleCode.MANAGER)) {
            authorizationService.require(actor, SystemPermission.MANAGE_RENTALS);
        } else if (!actor.hasRole(RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only operators can trigger return auto-settlement");
        }
        return autoSettleExpiredReturns();
    }

    public int autoSettleExpiredReturns() {
        // The 72-hour deadline only escalates and reminds. It must never create
        // customer consent, a refund, a collection, or a completed return case.
        return 0;
    }

    /**
     * Marks a missed return appointment and creates an operational recovery task.
     * The scheduler never moves customer goods by itself. Staff must record the
     * two-person handover, inventory evidence, and temporary storage location.
     */
    public int escalateNoShowReturns() {
        Instant now = Instant.now();
        List<ReturnCase> candidates = returnCaseRepository
            .findByStatusInAndRecoveryEligibleAtLessThanEqualAndRecoveryTaskCreatedAtIsNull(
                List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled), now);
        int processed = 0;
        for (ReturnCase candidate : candidates) {
            try {
                Boolean applied = settlementTransactions == null
                    ? escalateNoShowReturn(candidate, now)
                    : new org.springframework.transaction.support.TransactionTemplate(settlementTransactions)
                        .execute(status -> escalateNoShowReturn(candidate, now));
                if (Boolean.TRUE.equals(applied)) processed++;
            } catch (Exception ignored) {
                // A failed row must not prevent the next return case from being escalated.
            }
        }
        return processed;
    }

    private boolean escalateNoShowReturn(ReturnCase candidate, Instant now) {
        if (candidate.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && rentalRepository.findByIdForUpdate(candidate.getRental().getId()).isEmpty()) return false;
        ReturnCase current = returnCaseRepository.findByIdForUpdate(candidate.getId()).orElse(null);
        if (current == null
            || (current.getStatus() != ReturnCaseStatus.requested && current.getStatus() != ReturnCaseStatus.scheduled)
            || current.getRecoveryEligibleAt() == null
            || current.getRecoveryEligibleAt().isAfter(now)
            || current.getRecoveryTaskCreatedAt() != null
            || ReturnAttendancePolicy.protectsFromRecovery(current)) {
            return false;
        }

        boolean removalDeadline = current.getReturnType() == ReturnType.INVENTORY_MISMATCH;
        if (removalDeadline) {
            Rental rental = current.getRental();
            if (rental.getActualReturnedAt() != null || current.getInspectedAt() != null || current.getReadyForInspectionAt() != null
                || rental.getInventoryEscalatedAt() != null
                || rental.getInventoryRemovalDeadlineAt() == null || now.isBefore(rental.getInventoryRemovalDeadlineAt())) {
                return false;
            }
            if (violationRecovery != null) return violationRecovery.detect(rental, current,
                com.storagehub.domain.model.ViolationRecovery.Reason.REMOVAL_OVERDUE,
                rental.getInventoryRemovalDeadlineAt(), now, true);
        }
        if (maintenanceTaskRepository == null) {
            throw new IllegalStateException("Maintenance task repository is not available");
        }
        MaintenanceTask recoveryTask = findNoShowRecoveryTask(current);
        if (recoveryTask == null) {
            MaintenanceTask task = new MaintenanceTask();
            task.setFacility(current.getFacility());
            task.setStorageUnit(current.getStorageUnit());
            task.setReturnCase(current);
            task.setRental(current.getRental());
            task.setTitle(removalDeadline ? "Thu hồi hàng do quá hạn đưa hàng sai lệch ra khỏi kho" : NO_SHOW_RECOVERY_TASK_TITLE);
            task.setTaskType(MaintenanceTaskType.maintenance);
            task.setRecoveryType(MaintenanceRecoveryType.RETURN_NO_SHOW);
            task.setReason((removalDeadline ? "Đã hết hạn đưa hàng sai lệch ra khỏi kho. "
                : "Khách hàng đã quá thời gian hẹn trả kho và thời gian ân hạn. ")
                + "Không tự động di chuyển hàng. Cần hai nhân viên kiểm kê, chụp bằng chứng, niêm phong "
                + "và ghi nhận khu vực lưu tạm trước khi hoàn tất.");
            task.setPriority(TaskPriority.high);
            task.setStatus(MaintenanceTaskStatus.open);
            task.setDueAt(LocalDate.now(BusinessTime.ZONE));
            task.setDeadlineAt(now.plus(Math.max(1, noShowRecoverySlaHours), ChronoUnit.HOURS));
            recoveryTask = maintenanceTaskRepository.save(task);
        }

        current.setNoShowAt(now);
        current.setRecoveryTaskCreatedAt(now);
        current.setRecoveryNotes((removalDeadline ? "Đã hết hạn đưa hàng sai lệch ra khỏi kho. " : "Khách không đến ngày trả kho. ")
            + "Hàng chỉ được di chuyển sau khi Staff hoàn tất kiểm kê, bằng chứng và bàn giao hai người.");
        current.setStatus(ReturnCaseStatus.recovery_required);
        ReturnCase saved = returnCaseRepository.save(current);
        if (removalDeadline) {
            auditLogService.recordMutation((User) null, "INVENTORY_REMOVAL_EXPIRED", "return", saved.getId(),
                saved.getFacility().getId(), null, Map.of("dueAt", saved.getRecoveryEligibleAt(), "recoveryTaskId", recoveryTask.getId()));
        }

        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.RETURN,
            removalDeadline ? "Đã hết hạn đưa hàng ra khỏi kho" : "Lịch trả kho đã quá hạn",
            "Hồ sơ đã chuyển sang chờ thu hồi. Hàng chưa được tự động di chuyển. Vui lòng liên hệ cơ sở để thống nhất thời gian xử lý.",
            saved.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            saved.getFacility().getId(),
            NotificationType.RETURN,
            removalDeadline ? "Cần thu hồi hàng do quá hạn đưa hàng ra" : "Cần xử lý khách không đến trả kho",
            "Yêu cầu trả kho của gian " + saved.getStorageUnit().getCode()
                + " đã quá hạn xử lý. Cần phân công hai nhân viên kiểm kê và ghi nhận bàn giao hàng trước khi di chuyển.",
            saved.getId()
        );
        return true;
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
        boolean mismatchReturn = returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH;
        var previousStatement = mismatchReturn ? mismatchStatementAmounts(returnCase) : Map.<String, BigDecimal>of();
        long previousVersion = returnCase.getSettlementVersion();

        BigDecimal safeDamage = safeFee(request.damageFee());
        BigDecimal safeCleaning = safeFee(request.cleaningFee());
        BigDecimal safeLostItem = safeFee(request.lostItemFee());
        BigDecimal safeOverdue = safeFee(request.overdueFee());
        var verifiedFinance=captureFinance(returnCase);
        if ((returnCase.isSetupEarlyExit() || returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH) && (safeDamage.compareTo(returnCase.getDamageFee()) > 0
            || safeCleaning.compareTo(returnCase.getCleaningFee()) > 0 || safeLostItem.compareTo(returnCase.getLostItemFee()) > 0))
            throw ApiExceptions.conflict("Khi xem xét quyết toán trong thời gian chuẩn bị chỉ được giảm phí kiểm kê đã có chứng cứ; không tự thêm phí mới.");
        BigDecimal safeOutstanding = verifiedFinance.debt();
        safeOverdue=settlementOverdueFee(returnCase,safeOverdue,verifiedFinance);
        if (returnCase.isSetupEarlyExit()) safeOverdue = BigDecimal.ZERO;

        BigDecimal deductions = safeDamage
            .add(safeCleaning)
            .add(safeLostItem)
            .add(safeOverdue)
            .add(safeOutstanding)
            .setScale(2, RoundingMode.HALF_UP);

        var activePricing = activeReturnCalculation(returnCase);
        BigDecimal calculatedEarlyTerminationCharge = activePricing == null
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : activePricing.totalRentalCharge();
        BigDecimal calculatedRentalRefund = activePricing == null
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : activePricing.rentalRefundAmount();
        var mismatchCalculation = mismatchReturn ? setupExitCalculation(returnCase) : null;
        if (mismatchReturn && mismatchCalculation == null)
            throw ApiExceptions.conflict("Thiếu bảng tính thời gian sử dụng của hồ sơ kiểm kê sai lệch.");
        BigDecimal earlyTerminationCharge = mismatchReturn ? mismatchCalculation.totalRentalCharge() : returnCase.isSetupEarlyExit()
            ? zeroIfNull(returnCase.getEarlyTerminationFee())
            : (activePricing != null ? calculatedEarlyTerminationCharge : zeroIfNull(returnCase.getEarlyTerminationCharge()));
        BigDecimal rentalRefund = mismatchReturn ? zeroIfNull(returnCase.getRentalRefundCredit()) : returnCase.isSetupEarlyExit()
            ? zeroIfNull(returnCase.getRentalRefundCredit())
            // The immutable pricing snapshot is authoritative for an active
            // early return. A previously persisted amount may have been saved
            // before the rental-refund projection was recalculated.
            : (activePricing != null ? calculatedRentalRefund : zeroIfNull(returnCase.getRentalRefundAmount()));
        // Capture the approved amounts before this review overwrites them.
        BigDecimal previousDeductions = zeroIfNull(returnCase.getTotalDeductions());
        BigDecimal previousRefund = zeroIfNull(returnCase.getNetRefundAmount());
        BigDecimal previousDue = zeroIfNull(returnCase.getAmountDueFromCustomer());
        var settlement = calculateSettlement(returnCase, deductions, earlyTerminationCharge, rentalRefund);
        requireSettlementLimit(settlement.deductions());
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
        returnCase.setTotalDeductions(settlement.deductions());
        returnCase.setNetRefundAmount(settlement.refund());
        returnCase.setAmountDueFromCustomer(settlement.due());

        if (request.proposedUnitStatus() != null) {
            returnCase.setProposedUnitStatus(request.proposedUnitStatus());
        }

        // A dispute resolution rewrites the statement amounts. The approved statement and the
        // customer confirmation of the previous version are therefore no longer valid: the case
        // must go back to the customer for a renewed confirmation instead of moving straight to
        // collection or refund. Only an unchanged statement may continue to the money step.
        boolean amountsChanged = previousDeductions.compareTo(settlement.deductions()) != 0
            || previousRefund.compareTo(settlement.refund()) != 0
            || previousDue.compareTo(settlement.due()) != 0
            || mismatchReturn && (!previousStatement.equals(mismatchStatementAmounts(returnCase))
                || !java.util.Objects.equals(returnCase.getSettlementLedgerRevision(), verifiedFinance.ledgerRevision()));
        if (mismatchReturn) returnCase.setSettlementLedgerRevision(verifiedFinance.ledgerRevision());
        if (amountsChanged) {
            returnCase.setCustomerConfirmed(false);
            returnCase.setCustomerConfirmedAt(null);
            returnCase.setCustomerDecision(null);
            returnCase.setCustomerDecisionNote(null);
            returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
            publishMismatchStatement(returnCase, returnCase.getReviewedAt());
        } else if (settlement.due().signum() > 0) {
            returnCase.setStatus(ReturnCaseStatus.payment_due);
            startMismatchPaymentDeadline(returnCase, returnCase.getReviewedAt());
        } else if (settlement.refund().signum() > 0) {
            markRefundPending(returnCase);
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
            mismatchReturn ? Map.of("version", previousVersion, "amounts", previousStatement) : null,
            toResponse(saved)
        );

        notificationService.createNotification(
            saved.getCustomer().getId(),
            NotificationType.RETURN,
            amountsChanged
                ? "Bảng kê quyết toán đã được điều chỉnh, cần bạn xác nhận lại"
                : "Kết quả giải quyết khiếu nại quyết toán trả kho",
            amountsChanged
                ? "Manager đã xem xét lại và điều chỉnh bảng kê cho gian " + saved.getStorageUnit().getCode()
                    + ": " + request.resolutionNote()
                    + " Vui lòng kiểm tra và xác nhận lại bảng kê mới. Hệ thống không tự đồng ý thay bạn."
                : "Manager đã xem xét lại quyết toán cho gian " + saved.getStorageUnit().getCode() + ": " + request.resolutionNote(),
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
        requireCurrentEarlyReturnAmounts(returnCase);
        var recoveryGuard = recoveryGuard();
        if (recoveryGuard != null) {
            recoveryGuard.requireManualSettlement(returnCase);
        }
        boolean vnpay = "VNPAY".equalsIgnoreCase(request.paymentMethod())
            || "VNPAY_GATEWAY".equalsIgnoreCase(request.paymentMethod());
        if (returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH) {
            if (returnCase.getStatus() != ReturnCaseStatus.refund_pending || zeroIfNull(returnCase.getNetRefundAmount()).signum() <= 0)
                throw ApiExceptions.conflict("Hồ sơ chưa có khoản hoàn tiền đang chờ xử lý.");
            if (vnpay)
                throw ApiExceptions.conflict("Vui lòng ghi nhận hoàn tiền đã đối chiếu bằng biên lai cho hồ sơ sai lệch kiểm kê.");
            requireFinance().requireCurrent(returnCase);
        }
        if (hasTwoMismatchObligations(returnCase) && !requireFinance().hasCollectionReceipt(returnCase))
            throw ApiExceptions.conflict("Hồ sơ cần đủ biên lai thực thu và thực hoàn. Vui lòng đối soát thu tiền trước và ghi hoàn bằng biên lai.");
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
            if (request.receiptFileId() == null) {
                throw ApiExceptions.validation("Vui lòng đính kèm biên lai thực hoàn.", null);
            }
            if (returnCase.getStatus() != ReturnCaseStatus.refund_pending
                || returnCase.getNetRefundAmount() == null || returnCase.getNetRefundAmount().signum() <= 0
                || returnCase.getNetRefundAmount().compareTo(request.amount()) != 0) {
                throw ApiExceptions.conflict("Số tiền thực hoàn không khớp với bảng kê đang chờ hoàn tiền.");
            }
            String method = request.paymentMethod() != null && !request.paymentMethod().isBlank()
                ? request.paymentMethod() : "BANK_TRANSFER";
            String txRef = returnSettlementReference(returnCase, method, request.transactionReference());

            if (finance() != null) {
                finance().receipt(actor, returnCase, method, txRef, request.receiptFileId());
                finance().settle(returnCase, actor.userId(), txRef, true);
            }
            settlementReference = txRef;
        }

        returnCase.setStatus(ReturnCaseStatus.completed);
        returnCase.setCompletedAt(Instant.now());
        returnCase.setSettlementPaidAt(Instant.now());
        returnCase.setSettlementPaymentId(settlementReference);

        finalizeRentalAndUnit(returnCase);

        ReturnCase saved = returnCaseRepository.save(returnCase);
        if (saved == null) saved = returnCase;

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

    private String returnSettlementReference(ReturnCase returnCase, String method, String reference) {
        if ("CASH".equals(method)) return "TM-" + returnCase.getId() + (hasTwoMismatchObligations(returnCase)
            ? returnCase.getStatus() == ReturnCaseStatus.payment_due ? "-THU" : "-HOAN" : "");
        if (!"BANK_TRANSFER".equals(method)) {
            throw ApiExceptions.validation("Vui lòng chọn phương thức thu hoặc hoàn tiền hợp lệ.", null);
        }
        if (reference == null || reference.isBlank()) {
            throw ApiExceptions.validation("Vui lòng nhập mã giao dịch chuyển khoản.", null);
        }
        return reference.strip();
    }

    private boolean hasTwoMismatchObligations(ReturnCase rc) {
        return rc.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && zeroIfNull(rc.getAmountDueFromCustomer()).signum() > 0 && zeroIfNull(rc.getNetRefundAmount()).signum() > 0;
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
        lockRecoveryReturn(rc);
        boolean cashSettlement = "CASH".equals(request.paymentMethod());
        String cashReceiptCode = "TM-" + rc.getId();
        boolean twoObligations = hasTwoMismatchObligations(rc);
        if (twoObligations && (rc.getStatus() == ReturnCaseStatus.refund_pending || rc.getStatus() == ReturnCaseStatus.completed)) {
            String reference = request.transactionReference() == null ? null : request.transactionReference().strip();
            var finance = requireFinance();
            if (finance.matchesMismatchReceipt(rc, request.paymentMethod(), cashSettlement ? cashReceiptCode + "-THU" : reference,
                request.receiptFileId(), request.amount())
                || cashSettlement && finance.matchesMismatchReceipt(rc, request.paymentMethod(), cashReceiptCode + "-HOAN",
                    request.receiptFileId(), request.amount())) return toResponse(rc);
            if (rc.getStatus() == ReturnCaseStatus.completed)
                throw ApiExceptions.conflict("Yêu cầu gửi lại không khớp với biên lai đã lưu.");
            if (!finance.hasCollectionReceipt(rc))
                throw ApiExceptions.conflict("Cần ghi nhận biên lai thu tiền trước khi hoàn tiền.");
        }
        if (rc.getStatus() == ReturnCaseStatus.completed) {
            BigDecimal settledAmount = zeroIfNull(rc.getAmountDueFromCustomer()).signum() > 0
                ? rc.getAmountDueFromCustomer() : rc.getNetRefundAmount();
            String reference = request.transactionReference();
            if (cashSettlement && (reference == null || reference.isBlank()
                || cashReceiptCode.equals(rc.getSettlementPaymentId()))) {
                reference = rc.getSettlementPaymentId();
            }
            if (reference != null) reference = reference.strip();
            if (settledAmount == null || request.amount() == null || settledAmount.compareTo(request.amount()) != 0
                || !requireFinance().recordedReceiptMatches(rc, request.paymentMethod(), reference, request.receiptFileId())) {
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
        requireCurrentEarlyReturnAmounts(rc);
        var guard=recoveryGuard();
        if(guard!=null) {
            guard.requireManualSettlement(rc);
        }
        if (finance() == null) {
            throw ApiExceptions.conflict("Financial reconciliation is unavailable; the return case cannot be settled");
        }
        String reference = returnSettlementReference(rc, request.paymentMethod(), request.transactionReference());
        finance().receipt(actor, rc, request.paymentMethod(), reference, request.receiptFileId());
        if (twoObligations && rc.getStatus() == ReturnCaseStatus.payment_due) {
            markRefundPending(rc);
            returnCaseRepository.saveAndFlush(rc);
            auditLogService.recordMutation("RETURN_COLLECTION_RECORDED", "return", rc.getId(), rc.getFacility().getId(),
                null, Map.of("reference", reference, "amount", expectedAmount, "receiptFileId", request.receiptFileId()));
            notificationService.createFacilityOperationsNotifications(rc.getFacility().getId(), NotificationType.RETURN,
                "Đã thu tiền, còn khoản hoàn", "Đối chiếu và ghi biên lai hoàn tiền để hoàn tất hồ sơ.", rc.getId());
            return toResponse(rc);
        }
        finance().settle(rc, actor.userId(), reference, true);
        rc.setStatus(ReturnCaseStatus.completed);
        rc.setCompletedAt(Instant.now());
        rc.setSettlementPaidAt(Instant.now());
        rc.setSettlementPaymentId(reference);
        returnCaseRepository.saveAndFlush(rc);
        finalizeRentalAndUnit(rc);
        if (rc.getReturnType() == ReturnType.INVENTORY_MISMATCH && rc.getRental() != null) {
            rc.getRental().setInventoryPending(false);
            rentalRepository.save(rc.getRental());
            if (inventoryCheckInRepository != null && rc.getRental().getReservation() != null) {
                inventoryCheckInRepository.findByReservation_Id(rc.getRental().getReservation().getId())
                    .ifPresent(ci -> {
                        ci.setStatus(com.storagehub.domain.model.CheckInStatus.completed);
                        inventoryCheckInRepository.save(ci);
                    });
            }
        }
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
        String search = q != null ? q.trim() : null;
        Page<ReturnCase> cases = actor.hasRole(RoleCode.STAFF)
            ? returnCaseRepository.searchReturnCases(facilityId, status, allowedFacilityIds, search,
                PageRequest.of(page, pageSize), LocalDate.now(BusinessTime.ZONE))
            : returnCaseRepository.searchReturnCases(facilityId, status, allowedFacilityIds, search, pageable);
        Page<ReturnCaseResponse> result = cases.map(this::toResponse);

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
            guard.requireActiveTask(rental);
        }
        var rc=new ReturnCase();rc.setRental(rental);rc.setFacility(rental.getFacility());rc.setStorageUnit(rental.getStorageUnit());
        rc.setCustomer(rental.getCustomer());rc.setRequestedBy(userRepository.findById(actor.userId()).orElseThrow(()->ApiExceptions.notFound("Staff not found")));
        rc.setRequestedAt(Instant.now());rc.setScheduledDate(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        rc.setCustomerNotes("OVERDUE_REPOSSESSION - hồ sơ do nhân viên lập từ nhiệm vụ thu hồi");
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
            guard.requireReceipt(rc,reference);
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
            guard.requireReadyToRelease(rc);
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
    private Calculation calculateSettlement(ReturnCase rc, BigDecimal deductions, BigDecimal rentalCharge, BigDecimal rentalRefund) {
        BigDecimal securityDeductions = zeroIfNull(deductions).setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = zeroIfNull(rc.getDepositAmount()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rentalCredit = zeroIfNull(rentalRefund).setScale(2, RoundingMode.HALF_UP);

        // An inventory-mismatch return refunds the prepaid rent but must still charge for the
        // days the unit was actually used (verified usage, no fixed early-termination fee).
        // rentalCredit holds the full prepaid rent here, so the usage charge is deducted from it.
        if (rc.getReturnType() == ReturnType.INVENTORY_MISMATCH && zeroIfNull(rentalCharge).signum() > 0) {
            BigDecimal usageCharge = zeroIfNull(rentalCharge).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netRentalCredit = rentalCredit.subtract(usageCharge)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal depositBalance = deposit.subtract(securityDeductions);
            BigDecimal totalFees = securityDeductions.add(usageCharge).setScale(2, RoundingMode.HALF_UP);
            return new Calculation(totalFees,
                netRentalCredit.add(depositBalance.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP),
                depositBalance.negate().max(BigDecimal.ZERO).add(usageCharge.subtract(rentalCredit).max(BigDecimal.ZERO))
                    .setScale(2, RoundingMode.HALF_UP));
        }

        // During setup the agreed 20% retained amount is already represented by
        // rentalRefund. Damage can consume the one-month deposit only; any excess
        // becomes an amount due instead of silently consuming the 80% refund.
        if (rc.isSetupEarlyExit()) {
            BigDecimal depositBalance = deposit.subtract(securityDeductions);
            return new Calculation(securityDeductions,
                rentalCredit.add(depositBalance.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP),
                depositBalance.negate().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        }

        // Once the paid rental period has started, fees consume the deposit first,
        // then the refund for whole unused months, and only the remaining excess
        // is collected from the customer.
        BigDecimal available = deposit.add(rentalCredit);
        // Mismatch returns carry the full prepaid rent as credit, before usage.
        BigDecimal totalDeductions = rc.getReturnType() == ReturnType.INVENTORY_MISMATCH
            ? securityDeductions.add(zeroIfNull(rentalCharge)) : securityDeductions;
        BigDecimal balance = available.subtract(totalDeductions);
        return new Calculation(totalDeductions,
            balance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
            balance.negate().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
    }

    private Calculation expectedEarlyReturnSettlement(ReturnCase rc) {
        BigDecimal deductions = zeroIfNull(rc.getDamageFee()).add(zeroIfNull(rc.getCleaningFee()))
            .add(zeroIfNull(rc.getLostItemFee())).add(zeroIfNull(rc.getOverdueFee()))
            .add(zeroIfNull(rc.getOutstandingFee()));
        // Rebuild the rental credit from the immutable pricing snapshot. Older return
        // cases can expose activeReturnPolicy.rentalRefundAmount while their persisted
        // rentalRefundAmount/netRefundAmount still contain the deposit-only amount.
        // Using the persisted value here would silently omit the unused rental months
        // when the customer confirms or staff completes the refund.
        var activePricing = activeReturnCalculation(rc);
        BigDecimal rentalCharge = activePricing == null
            ? zeroIfNull(rc.getEarlyTerminationCharge())
            : activePricing.totalRentalCharge();
        BigDecimal rentalRefund = activePricing == null
            ? zeroIfNull(rc.getRentalRefundAmount())
            : activePricing.rentalRefundAmount();
        return calculateSettlement(rc, deductions, rentalCharge, rentalRefund);
    }

    private boolean hasOutdatedEarlyReturnAmounts(ReturnCase rc) {
        if (rc.getStatus() == ReturnCaseStatus.completed || rc.getInspectedAt() == null
            || rc.isSetupExitRequested() || !rc.isActiveReturnRepricingRequired()) {
            return false;
        }
        var expected = expectedEarlyReturnSettlement(rc);
        return expected.deductions().compareTo(zeroIfNull(rc.getTotalDeductions())) != 0
            || expected.refund().compareTo(zeroIfNull(rc.getNetRefundAmount())) != 0
            || expected.due().compareTo(zeroIfNull(rc.getAmountDueFromCustomer())) != 0;
    }

    private void requireCurrentEarlyReturnAmounts(ReturnCase rc) {
        if (hasOutdatedEarlyReturnAmounts(rc)) {
            var expected = expectedEarlyReturnSettlement(rc);
            var activePricing = activeReturnCalculation(rc);
            if (activePricing != null) {
                rc.setEarlyTerminationCharge(activePricing.totalRentalCharge());
                rc.setRentalRefundAmount(activePricing.rentalRefundAmount());
            }
            rc.setTotalDeductions(expected.deductions());
            rc.setNetRefundAmount(expected.refund());
            rc.setAmountDueFromCustomer(expected.due());
        }
    }
    private BigDecimal settlementOverdueFee(ReturnCase rc,BigDecimal requested,com.storagehub.service.ledger.ReturnFinancialReconciliation.View view) {
        try {
            return view.unpostedOverdueFee();
        } catch (Exception e) {
            return requested != null ? requested : BigDecimal.ZERO;
        }
    }
    /**
     * Deliberately does not enforce a cap. The business has not yet decided the final rule for
     * rounding and for limiting the usage fee against the total rent, and inventing a cap here
     * would silently change how much a customer owes or gets back. The call sites are kept so the
     * rule can be added in one place once it is decided.
     */
    private void requireSettlementLimit(BigDecimal total) {
        // No limit is applied until the business rule is confirmed. See the open-decisions report.
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ReturnCaseResponse recalculateRecoverySettlement(ActorPrincipal actor,UUID returnId) {
        authorizationService.require(actor,SystemPermission.PROCESS_RETURNS);
        var rc=returnCaseRepository.findById(returnId).orElseThrow(()->ApiExceptions.notFound("Return case not found"));
        facilityScopeService.assertCanOperate(actor,rc.getFacility().getId());lockRecoveryReturn(rc);
        boolean mismatch = rc.getReturnType() == ReturnType.INVENTORY_MISMATCH;
        if (mismatch && (rc.getStatus() == ReturnCaseStatus.completed || requireFinance().hasCollectionReceipt(rc)))
            throw ApiExceptions.conflict("Đã ghi nhận thực thu hoặc quyết toán hoàn tất. Cần xử lý điều chỉnh có chứng từ.");
        var before=toResponse(rc);
        var calculated=captureFinance(rc);
        BigDecimal total=rc.getDamageFee().add(rc.getCleaningFee()).add(rc.getLostItemFee()).add(calculated.unpostedOverdueFee()).add(calculated.debt());
        requireSettlementLimit(total);
        rc.setOverdueFee(calculated.unpostedOverdueFee());rc.setOutstandingFee(calculated.debt());rc.setTotalDeductions(total);
        BigDecimal deposit = rc.getDepositAmount() != null ? rc.getDepositAmount() : BigDecimal.ZERO;
        if (mismatch) {
            var pricing = setupExitCalculation(rc);
            if (pricing == null) throw ApiExceptions.conflict("Thiếu bảng tính tiền sử dụng kho đã xác minh.");
            var amounts = calculateSettlement(rc, total, pricing.totalRentalCharge(), rc.getRentalRefundCredit());
            rc.setEarlyTerminationCharge(pricing.totalRentalCharge());
            rc.setTotalDeductions(amounts.deductions());
            rc.setNetRefundAmount(amounts.refund());
            rc.setAmountDueFromCustomer(amounts.due());
            rc.setSettlementLedgerRevision(calculated.ledgerRevision());
        } else {
            rc.setNetRefundAmount(deposit.subtract(total).max(BigDecimal.ZERO));
            rc.setAmountDueFromCustomer(total.subtract(deposit).max(BigDecimal.ZERO));
        }
        rc.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);rc.setCustomerConfirmed(false);rc.setCustomerConfirmedAt(null);
        rc.setCustomerDecision(null);rc.setCustomerDecisionNote(null);
        publishMismatchStatement(rc, Instant.now());
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
    private static void publishMismatchStatement(ReturnCase rc, Instant now) {
        if (rc.getReturnType() != ReturnType.INVENTORY_MISMATCH) return;
        rc.setSettlementVersion(rc.getSettlementVersion() + 1);
        rc.setSettlementPublishedAt(now);
        rc.setSettlementPaymentDueAt(null);
    }

    private static void startMismatchPaymentDeadline(ReturnCase rc, Instant now) {
        if (rc.getReturnType() == ReturnType.INVENTORY_MISMATCH && rc.getSettlementPaymentDueAt() == null)
            rc.setSettlementPaymentDueAt(InventoryTimelinePolicy.dayStart(now, 8));
    }

    private Map<String, BigDecimal> mismatchStatementAmounts(ReturnCase rc) {
        var amounts = new java.util.LinkedHashMap<String, BigDecimal>();
        amounts.put("deposit", rc.getDepositAmount());
        amounts.put("damage", rc.getDamageFee());
        amounts.put("cleaning", rc.getCleaningFee());
        amounts.put("lostItems", rc.getLostItemFee());
        amounts.put("overdue", rc.getOverdueFee());
        amounts.put("outstanding", rc.getOutstandingFee());
        amounts.put("usageCharge", rc.getEarlyTerminationCharge());
        amounts.put("rentalCredit", rc.getRentalRefundAmount());
        amounts.put("deductions", rc.getTotalDeductions());
        amounts.put("refund", rc.getNetRefundAmount());
        amounts.put("collection", rc.getAmountDueFromCustomer());
        amounts.replaceAll((line, amount) -> zeroIfNull(amount).stripTrailingZeros());
        return amounts;
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
                case INVENTORY_MISMATCH -> com.storagehub.domain.model.RentalEndReason.CANCELLED_IN_SETUP;
                case EARLY_RETURN -> com.storagehub.domain.model.RentalEndReason.EARLY_RETURN;
                case NORMAL -> com.storagehub.domain.model.RentalEndReason.NORMAL;
            });
            if (rental.getActualReturnedAt() == null)
                rental.setActualReturnedAt(returnCase.getSetupReturnedAt() != null ? returnCase.getSetupReturnedAt() : Instant.now());
            rental.setSetupExitReviewRequired(false);
            rental.setCompletedAt(Instant.now());
            rentalRepository.save(rental);
        }

        StorageUnit unit = returnCase.getStorageUnit();
        if (unit != null && returnCase.getReturnType() != ReturnType.INVENTORY_MISMATCH) {
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

    private BigDecimal calculatedDamageFee(ReturnInspectionRequest request) {
        if (request.damageItems() == null) return safeFee(request.damageFee());
        try {
            BigDecimal calculated = ReturnDamagePricing.calculate(request.damageItems());
            if (!request.damageItems().isEmpty()
                && request.damageClassification() == com.storagehub.domain.model.DamageClassification.no_damage) {
                throw new IllegalArgumentException("Đã chọn hạng mục hư hỏng nhưng mức độ đang là không hư hại.");
            }
            if (!request.damageItems().isEmpty()
                && (request.damageClassification() == com.storagehub.domain.model.DamageClassification.minor_damage
                    || request.damageClassification() == com.storagehub.domain.model.DamageClassification.major_damage)) {
                String expectedCategory = request.damageClassification() == com.storagehub.domain.model.DamageClassification.major_damage ? "major" : "minor";
                boolean wrongCategory = request.damageItems().stream().anyMatch(item -> {
                    var price = ReturnDamagePricing.catalog().get(item.code());
                    return price == null || !expectedCategory.equals(price.category());
                });
                if (wrongCategory) throw new IllegalArgumentException("Hạng mục không khớp mức độ hư hỏng đã chọn.");
            }
            return calculated;
        } catch (IllegalArgumentException invalid) {
            throw ApiExceptions.validation(invalid.getMessage(), null);
        }
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
        MaintenanceTask noShowTask = null;
        if (maintenanceTaskRepository != null && rc.getId() != null) {
            var taskResult = maintenanceTaskRepository.findFirstByReturnCase_IdAndRecoveryTypeOrderByCreatedAtDesc(
                rc.getId(), MaintenanceRecoveryType.RETURN_NO_SHOW
            );
            noShowTask = taskResult == null ? null : taskResult.orElse(null);
        }
        var finances=returnFinances==null?null:returnFinances.getIfAvailable();
        var settlementReceipt=finances==null?null:finances.settledReceipt(rc,recovery!=null);
        var overdueSettlement=finances==null?null:finances.overdueSettlement(rc,recovery!=null);
        String settlementBlocker=null;
        Calculation displayedSettlement = hasOutdatedEarlyReturnAmounts(rc)
            ? expectedEarlyReturnSettlement(rc)
            : new Calculation(zeroIfNull(rc.getTotalDeductions()), zeroIfNull(rc.getNetRefundAmount()),
                zeroIfNull(rc.getAmountDueFromCustomer()));
        if (rc.isSetupExitRequested() && rc.getInspectedAt() != null && rc.getSetupReturnedAt() == null)
            settlementBlocker = "Hồ sơ thời gian chuẩn bị cũ thiếu chứng cứ trả kho và bảng kê theo điều khoản đã chấp nhận; cần đối soát thủ công.";
        if (rc.isSetupExitRequested() && rc.getReturnType() != ReturnType.INVENTORY_MISMATCH && rc.getStatus() != ReturnCaseStatus.completed
            && (rc.getRental().getReservation().getSourceQuote() == null
                || rc.getRental().getReservation().getSourceQuote().getSetupEarlyExitPolicyVersion() == null))
            settlementBlocker = "Đơn cũ chưa có điều khoản phí trả kho trong thời gian chuẩn bị đã chấp nhận. Cần cơ sở đối soát thủ công.";
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
            displayedSettlement.deductions(),
            displayedSettlement.refund(),
            displayedSettlement.due(),
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
            recovery==null
                ? (noShowTask == null ? null : noShowTask.getId())
                : recovery.taskId(),
            recovery==null
                ? (noShowTask == null || noShowTask.getStatus() == null ? null : noShowTask.getStatus().name().toUpperCase(java.util.Locale.ROOT))
                : recovery.status(),
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
            rc.getSetupEarlyExitPolicyVersion(), rc.getRentalRefundCredit(), rc.getEarlyTerminationFee(),
            rc.getNoShowAt(), rc.getRecoveryEligibleAt(), rc.getRecoveryTaskCreatedAt(),
            noShowTask == null || noShowTask.getRecoveryWitness() == null ? null : noShowTask.getRecoveryWitness().getId(),
            noShowTask == null || noShowTask.getRecoveryWitness() == null ? null : noShowTask.getRecoveryWitness().getFullName(),
            noShowTask == null ? null : noShowTask.getDeadlineAt(),
            rc.getReturnType() == ReturnType.INVENTORY_MISMATCH ? rc.getRental().getInventoryRemovalDeadlineAt() : null,
            rc.getLockInspectionResult(),
            rc.getReturnType() == ReturnType.INVENTORY_MISMATCH ? InventoryTimelinePolicy.resolveReturnWindowDays(rc.getRental(), rc) : null,
            rc.getReturnType() == ReturnType.INVENTORY_MISMATCH
                ? (rc.getReturnPolicyVersion() != null ? rc.getReturnPolicyVersion() : rc.getRental().getInventoryReturnPolicyVersion())
                : null,
            resolveEquipment(rc), rc.getCustomerClearedAt(), rc.getWarehouseEmptyVerified(),
            rc.getWarehouseEmptyVerifiedAt(), rc.getWarehouseEmptyVerifiedBy(), rc.getClearanceRevision(),
            rc.getDisplayCode(), rc.getRental().getDisplayCode(),
            ReturnAttendancePolicy.applies(rc) ? new ReturnCaseResponse.Attendance(rc.getReturnArrivedAt(), rc.getReturnArrivalEvidenceId(),
                ReturnAttendancePolicy.expiresAt(rc), rc.getReturnSecondAttemptId() != null ? 2 : rc.getReturnFirstAttemptId() != null ? 1 : 0,
                rc.getReturnIdentityVerifiedAt(), ReturnAttendancePolicy.reviewStartsAt(rc),
                rc.getReturnArrivalReviewAccepted(), rc.getPostReviewHandoverDueAt(), rc.getPostReviewHandoverStaffId(),
                rc.getReturnReverificationStartedAt(), rc.getReturnReverificationStaffId(), rc.getPostReviewHandoverAppointmentAt()) : null,
            rc.getSettlementVersion(), rc.getSettlementPublishedAt(), rc.getSettlementPaymentDueAt(),
            hasTwoMismatchObligations(rc) && finance() != null
                && (rc.getStatus() == ReturnCaseStatus.refund_pending || rc.getStatus() == ReturnCaseStatus.completed)
                ? finance().mismatchReceipts(rc) : List.of()
        );
    }

    private ReturnCaseResponse.Equipment resolveEquipment(ReturnCase rc) {
        if (rc == null) return null;
        com.storagehub.api.checkin.CheckInResponse.Equipment unitEquipment =
            palletInventoryService == null ? null : palletInventoryService.equipment(rc.getStorageUnit());
        com.storagehub.api.checkin.HandoverRecord handover = null;
        if (inventoryCheckInRepository != null && rc.getRental() != null && rc.getRental().getReservation() != null) {
            var checkIn = inventoryCheckInRepository.findByReservation_Id(rc.getRental().getReservation().getId()).orElse(null);
            if (checkIn != null && checkIn.getHandoverRecordJson() != null && !checkIn.getHandoverRecordJson().isBlank()) {
                try {
                    handover = objectMapper.readValue(checkIn.getHandoverRecordJson(), com.storagehub.api.checkin.HandoverRecord.class);
                } catch (Exception ignored) {}
            }
        }
        Integer handoverRacks = handover == null ? null : handover.rackCount();
        Integer handoverPallets = handover == null ? null : handover.palletCount();
        if (unitEquipment == null && handoverRacks == null && handoverPallets == null) return null;
        int rackCount = handoverRacks != null ? handoverRacks : (unitEquipment != null ? unitEquipment.rackCount() : 0);
        int frameCount = unitEquipment != null ? unitEquipment.frameCount() : rackCount;
        Integer palletCount = handoverPallets != null ? handoverPallets : (unitEquipment != null ? unitEquipment.palletCount() : null);
        BigDecimal unitPrice = palletUnitPrice != null && palletUnitPrice.signum() > 0
            ? palletUnitPrice : BigDecimal.valueOf(300_000);
        return new ReturnCaseResponse.Equipment(rackCount, frameCount, palletCount, unitPrice);
    }

    private MaintenanceTask findNoShowRecoveryTask(ReturnCase returnCase) {
        if (maintenanceTaskRepository == null || returnCase == null || returnCase.getId() == null) return null;
        var result = maintenanceTaskRepository.findFirstByReturnCase_IdAndRecoveryTypeOrderByCreatedAtDesc(
            returnCase.getId(), MaintenanceRecoveryType.RETURN_NO_SHOW
        );
        return result == null ? null : result.orElse(null);
    }

    private MaintenanceTask requireNoShowRecoveryTask(ReturnCase returnCase) {
        MaintenanceTask task = findNoShowRecoveryTask(returnCase);
        if (task == null) {
            throw ApiExceptions.conflict("Chưa có nhiệm vụ thu hồi hàng cho hồ sơ khách không đến.");
        }
        return task;
    }

    private User requireRecoveryWitness(UUID witnessId, UUID facilityId, UUID actorId) {
        if (witnessId == null) {
            throw ApiExceptions.validation("Cần chọn nhân viên thứ hai cùng xác nhận việc thu hồi hàng.", null);
        }
        if (witnessId.equals(actorId)) {
            throw ApiExceptions.conflict("Nhân viên xác nhận thứ hai phải khác người đang hoàn tất biên bản.");
        }
        User witness = userRepository.findById(witnessId)
            .orElseThrow(() -> ApiExceptions.notFound("Nhân viên xác nhận thứ hai không tồn tại."));
        boolean isStaff = witness.getRoles() != null && witness.getRoles().stream()
            .anyMatch(role -> role.getCode() == RoleCode.STAFF);
        boolean hasFacilityScope = userFacilityScopeRepository.findByUser_IdAndFacility_Id(witnessId, facilityId)
            .map(scope -> scope.getScopeLevel().includes(com.storagehub.domain.model.FacilityScopeLevel.OPERATE))
            .orElse(false);
        if (witness.getStatus() != com.storagehub.domain.model.UserStatus.ACTIVE || !isStaff || !hasFacilityScope) {
            throw ApiExceptions.forbidden("Nhân viên xác nhận thứ hai phải là Staff đang hoạt động tại cơ sở này.");
        }
        return witness;
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

        Instant setupStartInstant = rental.getSetupStartedAt();
        if (setupStartInstant == null) return null;
        LocalDate setupStart = setupStartInstant.atZone(BusinessTime.ZONE).toLocalDate();
        boolean goodsMovedIn = rental.getMoveInCompletedAt() != null || rental.getInventoryVerifiedAt() != null;
        if (returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH) {
            // For a mismatch return, rental use starts only when the customer
            // explicitly confirms that goods have been moved into the unit.
            // Physical handover/setup may happen earlier and is not billable use.
            Instant moveInCompletedAt = rental.getMoveInCompletedAt();
            if (moveInCompletedAt == null) return null;
            LocalDate usageStart = moveInCompletedAt.atZone(BusinessTime.ZONE).toLocalDate();
            Instant actual = returnCase.getSetupReturnedAt() != null ? returnCase.getSetupReturnedAt()
                : returnCase.getInspectedAt();
            if (actual == null || snapshot.getMonthlyPrice() == null || snapshot.getNetRentalAmount() == null) return null;
            LocalDate endDate = actual.atZone(BusinessTime.ZONE).toLocalDate();
            if (rental.getInventoryRemovalDeadlineAt() != null && endDate != null) {
                LocalDate last = rental.getInventoryRemovalDeadlineAt().atZone(BusinessTime.ZONE).toLocalDate().minusDays(1);
                if (endDate.isAfter(last)) endDate = last;
            }
            int usedDays = EarlyTerminationPolicy.calendarUsageDays(usageStart, endDate);
            if (usedDays <= 0) return null;
            return EarlyTerminationPolicy.inventoryMismatchExit(
                snapshot.getNetRentalAmount(), snapshot.getMonthlyPrice(), usedDays, goodsMovedIn
            );
        }
        Instant setupEndInstant = returnCase.getSetupReturnedAt();
        if (setupEndInstant == null) return null;
        if (rental.getSetupCompletionDeadlineAt() != null) {
            int days = EarlyTerminationPolicy.calendarUsageDays(setupStart, setupEndInstant.atZone(BusinessTime.ZONE).toLocalDate());
            BigDecimal usage = EarlyTerminationPolicy.daysCharge(snapshot.getMonthlyPrice(), days);
            BigDecimal fee = zeroIfNull(returnCase.getEarlyTerminationFee());
            BigDecimal charge = usage.add(fee);
            return new EarlyTerminationPolicy.SetupExitCalculation(EarlyTerminationPolicy.SetupExitType.GOODS_MOVED_IN,
                fee, days, usage, charge, snapshot.getNetRentalAmount().subtract(charge).max(BigDecimal.ZERO));
        }
        long elapsedSeconds = Math.max(0, java.time.Duration.between(setupStartInstant, setupEndInstant).getSeconds());
        int usedDays = Math.max(1, (int) Math.ceil(elapsedSeconds / 86_400d));
        return EarlyTerminationPolicy.setupExit(
            snapshot.getNetRentalAmount(),
            snapshot.getReservationDepositAmount(),
            snapshot.getMonthlyPrice(),
            snapshot.getRentalMonths(),
            snapshot.getFreeSetupDays(),
            usedDays,
            goodsMovedIn,
            snapshot.getEarlyTerminationFeeCoefficient(),
            snapshot.getEarlyTerminationFeeCapMonths(),
            returnCase.getReturnType() == ReturnType.INVENTORY_MISMATCH
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
            snapshot == null ? EarlyTerminationPolicy.DEFAULT_NOTICE_DAYS : snapshot.getEarlyTerminationNoticeDays(),
            snapshot == null ? null : snapshot.getRentalMonths(),
            effectiveMonthlyPrice(snapshot)
        );
    }

    /**
     * The settlement screen must show the effective rent after the booked
     * package discount, not the undiscounted catalogue rate.
     */
    private BigDecimal effectiveMonthlyPrice(ReservationPricingSnapshot snapshot) {
        if (snapshot == null) return null;
        if (snapshot.getNetRentalAmount() != null && snapshot.getRentalMonths() > 0) {
            return snapshot.getNetRentalAmount()
                .divide(BigDecimal.valueOf(snapshot.getRentalMonths()), 2, RoundingMode.HALF_UP);
        }
        return snapshot.getMonthlyPrice();
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
