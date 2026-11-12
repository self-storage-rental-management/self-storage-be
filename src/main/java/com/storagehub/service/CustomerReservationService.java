package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.reservation.CancelReservationRequest;
import com.storagehub.api.reservation.CheckInAppointmentRequest;
import com.storagehub.api.reservation.CheckInDocumentResponse;
import com.storagehub.api.reservation.CheckInDocumentResponse.Kind;
import com.storagehub.api.reservation.CustomerPreparationCompleteRequest;
import com.storagehub.api.reservation.RentalStartConfirmation;
import com.storagehub.api.reservation.ReservationDetailResponse;
import com.storagehub.api.reservation.ReservationGoodsItemResponse;
import com.storagehub.api.reservation.ReservationResponse;
import com.storagehub.api.reservation.UpdateSetupGoodsRequest;
import com.storagehub.api.returns.CustomerCreateReturnRequest;
import com.storagehub.api.file.FileAssetResponse;
import com.storagehub.api.payment.CustomerPaymentReceiptResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.MaintenanceTaskType;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.domain.model.FileAssetStatus;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentComplaintStatus;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.ReservationCancellationType;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.PaymentComplaintRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.RefundRequestRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.ledger.LedgerStore;
import com.storagehub.service.rental.period.RentalPeriod;
import com.storagehub.time.BusinessTime;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerReservationService {
    @Autowired
    private ObjectProvider<ReturnSettlementService> returnSettlements;
    @Autowired
    private com.storagehub.domain.repo.ReturnCaseRepository setupReturns;
    @Autowired
    private com.storagehub.service.rental.period.ConfirmedRentalPeriods confirmedRentalPeriods;
    @Autowired
    private ObjectProvider<ReturnSettlementService> returnSettlementServices;
    @Autowired
    private PaymentProvenance paymentProvenance;
    @Autowired
    private ObjectMapper objectMapper;

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final CheckInRepository checkInRepository;
    private final FileAssetRepository fileAssetRepository;
    private final RentalRepository rentalRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitAssignmentRepository assignmentRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ObjectProvider<LedgerStore> ledgerStores;
    private final AccessCredentialService accessCredentialService;
    private final com.storagehub.domain.repo.ContractRepository contractRepository;
    @Autowired(required = false)
    private RefundRequestRepository refundRequestRepository;
    @Autowired(required = false)
    private MaintenanceTaskRepository maintenanceTaskRepository;
    @Autowired(required = false)
    private PaymentComplaintRepository paymentComplaintRepository;
    @Autowired(required = false)
    private ReservationCapacityService reservationCapacityService;

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Autowired
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        CheckInRepository checkInRepository,
        RentalRepository rentalRepository,
        StorageUnitRepository storageUnitRepository,
        UnitAssignmentRepository assignmentRepository,
        FileAssetRepository fileAssetRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService,
        ObjectProvider<LedgerStore> ledgerStores,
        @Autowired(required = false) AccessCredentialService accessCredentialService,
        @Autowired(required = false) com.storagehub.domain.repo.ContractRepository contractRepository
    ) {
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.checkInRepository = checkInRepository;
        this.rentalRepository = rentalRepository;
        this.storageUnitRepository = storageUnitRepository;
        this.assignmentRepository = assignmentRepository;
        this.fileAssetRepository = fileAssetRepository;
        this.snapshotRepository = snapshotRepository;
        this.goodsItemRepository = goodsItemRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.ledgerStores = ledgerStores;
        this.accessCredentialService = accessCredentialService;
        this.contractRepository = contractRepository;
    }

    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        CheckInRepository checkInRepository,
        RentalRepository rentalRepository,
        StorageUnitRepository storageUnitRepository,
        UnitAssignmentRepository assignmentRepository,
        FileAssetRepository fileAssetRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService,
        ObjectProvider<LedgerStore> ledgerStores
    ) {
        this(
            reservationRepository, paymentRepository, checkInRepository, rentalRepository,
            storageUnitRepository, assignmentRepository, fileAssetRepository, snapshotRepository,
            goodsItemRepository, auditLogService, notificationService, ledgerStores,
            null, null
        );
    }

    /** Compatibility constructor retained for existing service tests and integrations. */
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        CheckInRepository checkInRepository,
        RentalRepository rentalRepository,
        StorageUnitRepository storageUnitRepository,
        FileAssetRepository fileAssetRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService
    ) {
        this(reservationRepository, paymentRepository, checkInRepository, rentalRepository,
            storageUnitRepository, null, fileAssetRepository, snapshotRepository, goodsItemRepository,
            auditLogService, notificationService, null);
    }

    /** Compatibility constructor for callers that provide assignment support without a ledger store. */
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        CheckInRepository checkInRepository,
        RentalRepository rentalRepository,
        StorageUnitRepository storageUnitRepository,
        UnitAssignmentRepository assignmentRepository,
        FileAssetRepository fileAssetRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService
    ) {
        this(reservationRepository, paymentRepository, checkInRepository, rentalRepository,
            storageUnitRepository, assignmentRepository, fileAssetRepository, snapshotRepository,
            goodsItemRepository, auditLogService, notificationService, null);
    }

    /** Compatibility constructor for ledger-aware callers created before assignment support was added. */
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        CheckInRepository checkInRepository,
        RentalRepository rentalRepository,
        StorageUnitRepository storageUnitRepository,
        FileAssetRepository fileAssetRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService,
        ObjectProvider<LedgerStore> ledgerStores
    ) {
        this(reservationRepository, paymentRepository, checkInRepository, rentalRepository,
            storageUnitRepository, null, fileAssetRepository, snapshotRepository,
            goodsItemRepository, auditLogService, notificationService, ledgerStores);
    }

    /** Compatibility constructor retained for service unit tests and existing integrations. */
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService
    ) {
        this(reservationRepository, paymentRepository, null, null, null, null, null, snapshotRepository,
            goodsItemRepository, auditLogService, notificationService, null);
    }

    /** Compatibility constructor retained for the handover/receipt service tests. */
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        CheckInRepository checkInRepository,
        PaymentRepository paymentRepository,
        RentalRepository rentalRepository,
        StorageUnitRepository storageUnitRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService
    ) {
        this(reservationRepository, paymentRepository, checkInRepository, rentalRepository,
            storageUnitRepository, null, null, snapshotRepository, goodsItemRepository,
            auditLogService, notificationService, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> list(
        ActorPrincipal actor,
        ReservationStatus status,
        int page,
        int size,
        String correlationId
    ) {
        PageRequest pageable = createPageRequest(page, size);
        Page<Reservation> reservations;
        if (status == null) {
            reservations = reservationRepository.findAllByCustomer_IdAndArchivedAtIsNullAndStatusNot(
                actor.userId(), ReservationStatus.REJECTED, pageable
            );
        } else {
            reservations = reservationRepository.findAllByCustomer_IdAndStatusAndArchivedAtIsNull(
                actor.userId(), status, pageable
            );
        }
        return PageResponse.from(reservations.map(this::toSummaryResponse), correlationId);
    }

    @Transactional(readOnly = true)
    public ReservationDetailResponse getDetail(ActorPrincipal actor, UUID reservationId) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        return toDetailResponse(reservation);
    }

    @Transactional
    public ReservationDetailResponse updateSetupGoods(
        ActorPrincipal actor, UUID reservationId, UpdateSetupGoodsRequest request
    ) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Chỉ khách hàng mới được cập nhật hàng hóa trong thời gian chuẩn bị");
        }
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        Rental rental = rentalRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Không tìm thấy hồ sơ thuê trong thời gian chuẩn bị"));
        Instant now = Instant.now();
        if (rental.getSetupStartedAt() == null || !rental.getSetupStartedAt().isBefore(now)
            || rental.getSetupEndsAt() == null || !rental.getSetupEndsAt().isAfter(now)) {
            throw ApiExceptions.conflict("Chỉ được cập nhật hàng hóa trong thời gian chuẩn bị");
        }
        if (rental.getInventoryVerifiedAt() != null || reservation.getSetupGoodsRevisionUsedAt() != null) {
            throw ApiExceptions.conflict("Khai báo hàng hóa trong thời gian chuẩn bị đã bị khóa hoặc đã được sửa");
        }

        List<ReservationGoodsItem> existing = goodsItemRepository
            .findAllByReservation_IdOrderByCreatedAtAsc(reservationId);
        if (existing.size() != request.goodsItems().size()) {
                throw ApiExceptions.conflict("Không thể thêm hoặc xóa loại hàng trong lần sửa thời gian chuẩn bị. Chỉ được điều chỉnh thông tin hàng đã khai báo.");
        }
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal rackLengthCm = ReservationSnapshotView.unitTypeDecimal(reservation, "rackLengthM").movePointRight(2);
        BigDecimal rackWidthCm = ReservationSnapshotView.unitTypeDecimal(reservation, "rackWidthM").movePointRight(2);
        BigDecimal rackHeightCm = ReservationSnapshotView.unitTypeDecimal(reservation, "rackHeightM").movePointRight(2);
        var beforeGoods = existing.stream().map(this::goodsAuditSnapshot).toList();
        for (int index = 0; index < request.goodsItems().size(); index++) {
            var input = request.goodsItems().get(index);
            ReservationGoodsItem item = existing.get(index);
            if (!sameGoodsIdentity(item, input)) {
                throw ApiExceptions.conflict("Không thể đổi loại hàng hoặc vật liệu trong lần sửa thời gian chuẩn bị. Vui lòng liên hệ nhân viên cơ sở.");
            }
            BigDecimal itemVolume = input.getLengthCm().multiply(input.getWidthCm())
                .multiply(input.getHeightCm()).divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
            totalVolume = totalVolume.add(itemVolume.multiply(BigDecimal.valueOf(input.getQuantity())));
            totalWeight = totalWeight.add(input.getWeightPerItemKg().multiply(BigDecimal.valueOf(input.getQuantity())));
            if (!dimensionsFit(input.getLengthCm(), input.getWidthCm(), input.getHeightCm(),
                rackLengthCm, rackWidthCm, rackHeightCm)) {
                throw ApiExceptions.conflict("Kích thước hàng thứ " + (index + 1) + " không vừa khung kệ của loại kho đã đặt.");
            }
            item.setCustomGoodsName(clean(input.getCustomGoodsName()));
            item.setCustomMaterial(clean(input.getCustomMaterial()));
            item.setDescription(clean(input.getDescription()));
            item.setQuantity(input.getQuantity());
            item.setLengthCm(input.getLengthCm());
            item.setWidthCm(input.getWidthCm());
            item.setHeightCm(input.getHeightCm());
            item.setWeightKg(input.getWeightPerItemKg());
            item.setFragile(input.isFragile());
            item.setCustomerNote(clean(input.getCustomerNote()));
        }
        BigDecimal snapshotVolume = ReservationSnapshotView.unitTypeDecimal(reservation, "lengthM")
            .multiply(ReservationSnapshotView.unitTypeDecimal(reservation, "widthM"))
            .multiply(ReservationSnapshotView.unitTypeDecimal(reservation, "heightM"));
        BigDecimal usableRackVolume = ReservationSnapshotView.unitTypeDecimal(reservation, "rackLengthM")
            .multiply(ReservationSnapshotView.unitTypeDecimal(reservation, "rackWidthM"))
            .multiply(ReservationSnapshotView.unitTypeDecimal(reservation, "rackHeightM"))
            .multiply(new BigDecimal("0.80"));
        int requiredRacks = usableRackVolume.signum() > 0
            ? totalVolume.divide(usableRackVolume, 0, RoundingMode.CEILING).intValueExact()
            : Integer.MAX_VALUE;
        if (totalVolume.compareTo(snapshotVolume) > 0
            || requiredRacks > ReservationSnapshotView.unitTypeRackCount(reservation)) {
            throw ApiExceptions.conflict("Goods volume exceeds the storage unit capacity");
        }
        if (totalWeight.compareTo(ReservationSnapshotView.unitTypeDecimal(reservation, "maxLoadKg")) > 0) {
            throw ApiExceptions.conflict("Goods weight exceeds the storage unit capacity");
        }
        goodsItemRepository.saveAll(existing);
        reservation.setTotalGoodsVolumeM3(totalVolume.setScale(6, RoundingMode.HALF_UP));
        reservation.setTotalGoodsWeightKg(totalWeight.setScale(2, RoundingMode.HALF_UP));
        reservation.setGoodsCondition(clean(request.goodsCondition()));
        reservation.setSetupGoodsRevisionUsedAt(now);
        reservationRepository.saveAndFlush(reservation);
        auditLogService.recordMutation(
            reservation.getCustomer(), "SETUP_GOODS_DECLARATION_AMENDED", "Reservation", reservation.getId(),
            reservation.getFacility().getId(), beforeGoods,
            Map.of("revisionUsedAt", now, "itemCount", existing.size(),
                "goods", existing.stream().map(this::goodsAuditSnapshot).toList())
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Khách đã cập nhật khai báo hàng trong thời gian chuẩn bị",
            "Đơn " + reservation.getReservationCode() + " đã dùng lần sửa khai báo duy nhất trước kiểm kê.",
            reservation.getId()
        );
        return toDetailResponse(reservation);
    }

    @Transactional
    public ReservationDetailResponse markPreparationComplete(ActorPrincipal actor, UUID reservationId,
                                                              CustomerPreparationCompleteRequest request) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customers can report preparation completion");
        }
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Chưa có hồ sơ bàn giao để báo hoàn tất chuẩn bị."));
        if (checkIn.getStatus() == CheckInStatus.setup_completed) {
            return toDetailResponse(reservation);
        }
        if (checkIn.getStatus() != CheckInStatus.setup_in_progress) {
            throw ApiExceptions.conflict("Đơn chưa ở thời gian chuẩn bị hàng hoặc đã chuyển sang bước kiểm kê.");
        }
        Instant completedAt = Instant.now();
        checkIn.setStatus(CheckInStatus.setup_completed);
        checkIn.setSetupCompletedAt(completedAt);
        String customerNote = clean(request == null ? null : request.note());
        if (customerNote != null) {
            String existingReadinessNote = clean(checkIn.getReadinessNote());
            String mergedReadinessNote = existingReadinessNote == null
                ? "Ghi chú khách: " + customerNote
                : existingReadinessNote + " | Ghi chú khách: " + customerNote;
            checkIn.setReadinessNote(mergedReadinessNote.substring(0, Math.min(1000, mergedReadinessNote.length())));
        }
        checkInRepository.saveAndFlush(checkIn);
        if (checkIn.getRental() != null) {
            checkIn.getRental().setMoveInCompletedAt(completedAt);
            checkIn.getRental().setSetupReminderTwoDaysAt(completedAt);
            checkIn.getRental().setSetupReminderLastDayAt(completedAt);
            createInventoryTask(checkIn.getRental(), completedAt);
            rentalRepository.saveAndFlush(checkIn.getRental());
        }
        auditLogService.recordMutation(
            reservation.getCustomer(), "CUSTOMER_PREPARATION_COMPLETED", "CheckIn", checkIn.getId(),
            reservation.getFacility().getId(),
            Map.of("checkInStatus", CheckInStatus.setup_in_progress),
            Map.of("checkInStatus", CheckInStatus.setup_completed)
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Khách đã báo chuẩn bị hàng xong",
            "Đơn " + reservation.getReservationCode() + " đã sẵn sàng để Staff kiểm kê hàng hóa.",
            reservation.getId()
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Đã tạo yêu cầu kiểm kê hàng hóa",
            "Hệ thống đã chuyển yêu cầu kiểm kê hàng hóa của đơn "
                + reservation.getReservationCode() + " cho Staff cơ sở.",
            reservation.getId()
        );
        return toDetailResponse(reservation);
    }

    private void createInventoryTask(Rental rental, Instant now) {
        if (maintenanceTaskRepository == null
            || rental.getInventoryTaskCreatedAt() != null
            || maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(
                rental.getId(), MaintenanceTaskType.inventory_check,
                List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
            ).stream().findAny().isPresent()) {
            return;
        }
        MaintenanceTask task = new MaintenanceTask();
        task.setFacility(rental.getFacility());
        task.setStorageUnit(rental.getStorageUnit());
        task.setRental(rental);
        task.setTaskType(MaintenanceTaskType.inventory_check);
        task.setTitle("Kiểm kê hàng hóa đầu vào · " + rental.getStorageUnit().getCode());
        task.setReason(
            "Khách đã báo chuẩn bị hàng xong cho đơn "
                + rental.getReservation().getReservationCode()
                + ". Staff cần kiểm kê hàng hóa, không kiểm kê lại gian kho trống."
        );
        task.setPriority(TaskPriority.medium);
        task.setReportedBy(null);
        task.setStatus(MaintenanceTaskStatus.open);
        task.setDueAt(rental.getInventoryDueAt() == null
            ? null
            : rental.getInventoryDueAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate());
        maintenanceTaskRepository.saveAndFlush(task);
        rental.setInventoryTaskCreatedAt(now);
    }

    private boolean sameGoodsIdentity(ReservationGoodsItem stored, com.storagehub.api.reservation.GoodsItemRequest input) {
        if (stored.getCatalogCategoryId() != null || input.getCatalogCategoryId() != null) {
            if (!java.util.Objects.equals(stored.getCatalogCategoryId(), input.getCatalogCategoryId())) return false;
        } else if (stored.getCategory() != input.getCategory()) return false;
        if (stored.getCatalogMaterialId() != null || input.getCatalogMaterialId() != null) {
            return java.util.Objects.equals(stored.getCatalogMaterialId(), input.getCatalogMaterialId());
        }
        return java.util.Objects.equals(clean(stored.getMaterialName()), clean(input.getMaterialName()))
            && java.util.Objects.equals(clean(stored.getCustomMaterial()), clean(input.getCustomMaterial()));
    }

    private boolean dimensionsFit(BigDecimal a, BigDecimal b, BigDecimal c,
                                  BigDecimal x, BigDecimal y, BigDecimal z) {
        var goods = new java.util.ArrayList<>(List.of(a, b, c));
        var rack = new java.util.ArrayList<>(List.of(x, y, z));
        goods.sort(java.util.Comparator.naturalOrder());
        rack.sort(java.util.Comparator.naturalOrder());
        for (int index = 0; index < goods.size(); index++) {
            if (goods.get(index).compareTo(rack.get(index)) > 0) return false;
        }
        return true;
    }

    private Map<String, Object> goodsAuditSnapshot(ReservationGoodsItem item) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("goodsCode", item.getGoodsCode());
        value.put("catalogCategoryId", item.getCatalogCategoryId());
        value.put("catalogMaterialId", item.getCatalogMaterialId());
        value.put("categoryCode", item.getCategoryCode());
        value.put("categoryName", item.getCategoryName());
        value.put("materialCode", item.getMaterialCode());
        value.put("materialName", item.getMaterialName());
        value.put("description", item.getDescription());
        value.put("quantity", item.getQuantity());
        value.put("lengthCm", item.getLengthCm());
        value.put("widthCm", item.getWidthCm());
        value.put("heightCm", item.getHeightCm());
        value.put("weightKg", item.getWeightKg());
        value.put("fragile", item.isFragile());
        return value;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerPaymentReceiptResponse> paymentHistory(
        ActorPrincipal actor, int page, int size, String correlationId
    ) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customers can read their payment history");
        }
        PageRequest pageable = createPageRequest(page, size).withSort(
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))
        );
        return PageResponse.from(paymentRepository.findAllByReservation_Customer_Id(actor.userId(), pageable)
            .map(payment -> toPaymentResponse(payment, payment.getReservation().getStatus())), correlationId);
    }

    @Transactional(readOnly = true)
    public List<CustomerPaymentReceiptResponse> listPayments(ActorPrincipal actor, UUID reservationId) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        return paymentRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
            .stream()
            .map(payment -> toPaymentResponse(payment, reservation.getStatus()))
            .toList();
    }

    @Transactional
    public ReservationDetailResponse confirmReceipt(ActorPrincipal actor, UUID reservationId) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customer actors can confirm unit receipt");
        }

        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(
            reservationId, actor.userId()
        ).orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));

        // Repeated confirmation never recreates a rental, ledger or notifications.
        // Older early receipts may still need their verified setup rental activated.
        if (reservation.getStatus() == ReservationStatus.COMPLETED) {
            var existing = rentalRepository.findByReservationIdForUpdate(reservationId);
            if (existing.isPresent() && existing.get().getStatus() == RentalStatus.setup) {
                CheckIn completedCheckIn = checkInRepository.findByReservation_Id(reservationId)
                    .filter(item -> item.getStatus() == CheckInStatus.completed)
                    .orElseThrow(() -> ApiExceptions.conflict("Completed handover record was not found"));
                ReservationPricingSnapshot acceptedSnapshot = snapshotRepository.findByReservation_Id(reservationId)
                    .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
                requireReceiptTimeline(reservation, completedCheckIn, existing.get(), acceptedSnapshot);
                activateReceivedRental(existing.get());
            }
            return toDetailResponse(reservation);
        }

        if (reservation.getStatus() == ReservationStatus.PREPARING) {
            return toDetailResponse(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.AWAITING_CUSTOMER_RECEIPT) {
            throw ApiExceptions.conflict("Reservation is not waiting for customer receipt confirmation");
        }
        if (reservation.getAssignedUnit() == null) {
            throw ApiExceptions.conflict("Reservation has no assigned storage unit");
        }

        CheckIn checkIn = checkInRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Completed handover record was not found"));
        if (checkIn.getStatus() == CheckInStatus.handover_pending_receipt) {
            ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
                .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
            int setupDays = ReservationSnapshotView.requireSetupDays(reservation, snapshot);
            if (ledgerStores != null && ledgerStores.getIfAvailable() != null) {
                com.storagehub.service.ledger.FinancialContractSpecifications.validateInitial(snapshot);
            }
            Instant startedAt = Instant.now();
            java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
            java.time.LocalDate paidRentalStart = startedAt.atZone(zone).toLocalDate().plusDays(setupDays);
            Instant setupEndsAt = paidRentalStart.atStartOfDay(zone).toInstant();
            Rental rental = rentalRepository.findByReservationIdForUpdate(reservationId).orElseGet(Rental::new);
            if (rental.getId() != null) requireReceiptRentalStatus(rental);
            if (rental.getId() == null) {
                rental.setCustomer(reservation.getCustomer());
                rental.setFacility(reservation.getFacility());
                rental.setStorageUnit(reservation.getAssignedUnit());
                rental.setReservation(reservation);
                rental.setMonthlyPrice(snapshot.getMonthlyPrice());
            }
            rental.setStatus(setupEndsAt.isAfter(startedAt) ? RentalStatus.setup : RentalStatus.active);
            rental.setSetupStartedAt(startedAt);
            rental.setSetupEndsAt(setupEndsAt);
            rental.setTermsEffectiveAt(startedAt);
            rental.setStartDate(paidRentalStart);
            rental.setContractEndDate(paidRentalStart.plusMonths(snapshot.getRentalMonths()));
            rental.setInventoryDueAt(setupEndsAt.plusSeconds(3 * 86_400L));
            rental.setInventoryPending(true);
            rental = rentalRepository.saveAndFlush(rental);
            ensureSignedContract(reservation, rental);
            reservation.setStartDate(rental.getStartDate());
            reservation.setEndDate(rental.getContractEndDate());
            ReservationStatus previousStatus = reservation.getStatus();
            reservation.setStatus(ReservationStatus.PREPARING);
            Reservation saved = reservationRepository.saveAndFlush(reservation);
            checkIn.setStatus(CheckInStatus.setup_in_progress);
            checkIn.setSetupStartedAt(startedAt);
            checkIn.setSetupDueAt(setupEndsAt);
            checkIn.setRental(rental);
            checkInRepository.saveAndFlush(checkIn);
            if (accessCredentialService != null) {
                accessCredentialService.attachToRental(saved, rental);
            }
            if (confirmedRentalPeriods != null) {
                confirmedRentalPeriods.confirm(rental,
                    com.storagehub.service.rental.period.RentalPeriod.verified(
                        rental.getId(), rental.getStartDate(), rental.getContractEndDate(),
                        com.storagehub.service.rental.period.RentalPeriod.Convention.EXCLUSIVE,
                        "confirmed-handover:" + checkIn.getId()
                    ).orElseThrow(() -> ApiExceptions.conflict("Kỳ thuê bàn giao chưa đủ dữ liệu xác nhận.")));
            }
            auditLogService.recordMutation(
                saved.getCustomer(), "CUSTOMER_RECEIPT_CONFIRMED", "Reservation", saved.getId(),
                saved.getFacility().getId(),
                Map.of("status", previousStatus, "checkInStatus", CheckInStatus.handover_pending_receipt),
                Map.of("status", saved.getStatus(), "checkInStatus", CheckInStatus.setup_in_progress,
                    "rentalId", rental.getId())
            );
            notificationService.createNotification(
                saved.getCustomer().getId(), NotificationType.CHECKIN,
                "Đã xác nhận nhận kho",
                "Thời gian chuẩn bị miễn phí bắt đầu từ hôm nay, đến hết "
                    + paidRentalStart.minusDays(1) + ". Kỳ thuê tính tiền từ " + paidRentalStart + ".",
                saved.getId()
            );
            notificationService.createFacilityOperationsNotifications(
                saved.getFacility().getId(), NotificationType.CHECKIN,
                "Khách đã xác nhận nhận kho",
                "Đơn " + saved.getReservationCode()
                    + " đã chuyển sang thời gian chuẩn bị hàng.",
                saved.getId()
            );
            return toDetailResponse(saved);
        }
        if (checkIn.getStatus() != CheckInStatus.completed) {
            throw ApiExceptions.conflict("Storage unit handover is not completed");
        }

        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        ReservationSnapshotView.requireSetupDays(reservation,snapshot);
        if (ledgerStores != null && ledgerStores.getIfAvailable() != null) {
            com.storagehub.service.ledger.FinancialContractSpecifications.validateInitial(snapshot);
        }
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Thiếu hồ sơ thuê từ bàn giao thực tế, cần đối soát trước khi xác nhận nhận kho."));
        requireReceiptTimeline(reservation,checkIn,rental,snapshot);
        activateReceivedRental(rental);

        reservation.getAssignedUnit().setStatus(StorageUnitStatus.occupied);
        storageUnitRepository.saveAndFlush(reservation.getAssignedUnit());
        if (accessCredentialService != null) {
            accessCredentialService.attachToRental(reservation, rental);
        }
        ReservationStatus previousStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.COMPLETED);
        Reservation saved = reservationRepository.saveAndFlush(reservation);

        if(confirmedRentalPeriods!=null) confirmedRentalPeriods.confirm(rental,
            com.storagehub.service.rental.period.RentalPeriod.verified(rental.getId(),rental.getStartDate(),rental.getContractEndDate(),
                com.storagehub.service.rental.period.RentalPeriod.Convention.EXCLUSIVE,"confirmed-handover:"+checkIn.getId())
                .orElseThrow(() -> ApiExceptions.conflict("Kỳ thuê bàn giao chưa đủ dữ liệu xác nhận.")));
        auditLogService.recordMutation(
            saved.getCustomer(), "CUSTOMER_RECEIPT_CONFIRMED", "Reservation", saved.getId(),
            saved.getFacility().getId(),
            Map.of("status", previousStatus),
            Map.of("status", saved.getStatus(), "rentalId", rental.getId(), "rentalPeriodEvidence",
                com.storagehub.service.rental.period.RentalPeriodEvidence.handoverReceipt(rental,checkIn))
        );
        notificationService.createNotification(
            saved.getCustomer().getId(), NotificationType.CHECKIN,
            "Đã xác nhận nhận kho",
            "Đơn " + saved.getReservationCode() + " đã được xác nhận nhận kho theo hồ sơ bàn giao đã lưu.",
            saved.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            saved.getFacility().getId(), NotificationType.CHECKIN,
            "Khách hàng đã xác nhận nhận kho",
            "Khách hàng đã xác nhận nhận kho cho đơn " + saved.getReservationCode()
                + ". Hồ sơ bàn giao đã được xác nhận.",
            saved.getId()
        );
        return toDetailResponse(saved);
    }

    @Transactional(readOnly = true)
    public RentalStartConfirmation previewRentalStart(ActorPrincipal actor, UUID reservationId) {
        requireRentalStartCustomer(actor);
        Reservation reservation = reservationRepository.findByIdAndCustomer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy đơn giữ kho."));
        Rental rental = rentalRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Đơn chưa có hồ sơ thuê."));
        return rentalStartOffer(reservation, rental, Instant.now());
    }

    @Transactional
    public ReservationDetailResponse confirmRentalStart(
        ActorPrincipal actor, UUID reservationId, RentalStartConfirmation.Request request
    ) {
        requireRentalStartCustomer(actor);
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy đơn giữ kho."));
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Đơn chưa có hồ sơ thuê."));
        if (reservation.getStatus() == ReservationStatus.COMPLETED && rental.getStatus() == RentalStatus.active
            && rental.getInventoryVerifiedAt() != null && request.startDate().equals(rental.getStartDate())
            && rental.getContractEndDate() != null
            && request.lastPermittedDate().equals(rental.getContractEndDate().minusDays(1))) {
            return toDetailResponse(reservation);
        }

        Instant now = Instant.now();
        RentalStartConfirmation offer = rentalStartOffer(reservation, rental, now);
        if (!offer.startDate().equals(request.startDate()) || !offer.lastPermittedDate().equals(request.lastPermittedDate())) {
            throw ApiExceptions.conflict("Ngày bắt đầu hoặc ngày cuối kỳ thuê đã thay đổi. Vui lòng xem lại trước khi xác nhận.");
        }
        var contract = contractRepository.findByRental_Id(rental.getId())
            .filter(item -> item.getStatus() == com.storagehub.domain.model.ContractStatus.SIGNED)
            .orElseThrow(() -> ApiExceptions.conflict("Hồ sơ chưa có hợp đồng đã ký để xác nhận mốc kỳ thuê."));

        var oldDates = Map.of("startDate", rental.getStartDate(), "endDateExclusive", rental.getContractEndDate(),
            "setupEndsAt", rental.getSetupEndsAt());
        var endExclusive = offer.lastPermittedDate().plusDays(1);
        rental.setStartDate(offer.startDate());
        rental.setContractEndDate(endExclusive);
        rental.setSetupEndsAt(now);
        rental.setStatus(RentalStatus.active);
        reservation.setStartDate(offer.startDate());
        reservation.setEndDate(endExclusive);
        contract.setExpiresAt(endExclusive.atStartOfDay(BusinessTime.ZONE).toInstant());
        rentalRepository.saveAndFlush(rental);
        reservationRepository.save(reservation);
        contractRepository.save(contract);

        var period = RentalPeriod.verified(rental.getId(), offer.startDate(), endExclusive,
            RentalPeriod.Convention.EXCLUSIVE, "customer-rental-start:" + reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Mốc kỳ thuê xác nhận không hợp lệ."));
        confirmedRentalPeriods.confirm(rental, period);
        auditLogService.recordMutation(reservation.getCustomer(), "CUSTOMER_RENTAL_START_CONFIRMED", "Rental",
            rental.getId(), reservation.getFacility().getId(), oldDates,
            Map.of("startDate", offer.startDate(), "endDateExclusive", endExclusive, "setupEndsAt", now,
                "rentalMonths", offer.rentalMonths(), "confirmedByCustomer", true));
        notificationService.createNotification(reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Kỳ thuê đã bắt đầu",
            "Bạn đã xác nhận bắt đầu kỳ thuê gian " + rental.getStorageUnit().getCode()
                + " từ " + offer.startDate() + " đến hết " + offer.lastPermittedDate() + ".",
            reservationId);
        return toDetailResponse(reservation);
    }

    private void requireRentalStartCustomer(ActorPrincipal actor) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Chỉ khách hàng thuê kho được xác nhận bắt đầu kỳ thuê.");
        }
    }

    private RentalStartConfirmation rentalStartOffer(Reservation reservation, Rental rental, Instant now) {
        requireReceiptRentalStatus(rental);
        if (rental.getStatus() == RentalStatus.active) {
            throw ApiExceptions.conflict("Kỳ thuê của hồ sơ này đã bắt đầu.");
        }
        if (!RentalSetupLifecycleService.customerCanStartAfterInventory(rental, now)) {
            throw ApiExceptions.conflict("Chỉ xác nhận bắt đầu sớm sau khi khách báo chuẩn bị xong và nhân viên hoàn tất kiểm kê, trong thời gian miễn phí.");
        }
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservation.getId())
            .filter(item -> item.getStatus() == CheckInStatus.completed)
            .orElseThrow(() -> ApiExceptions.conflict("Chưa có biên bản kiểm kê hoàn tất."));
        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Thiếu báo giá đã chấp nhận của hồ sơ thuê."));
        requireReceiptTimeline(reservation, checkIn, rental, snapshot);
        var start = now.atZone(BusinessTime.ZONE).toLocalDate();
        return new RentalStartConfirmation(rental.getStartDate(), start,
            start.plusMonths(snapshot.getRentalMonths()).minusDays(1), snapshot.getRentalMonths());
    }

    private void ensureSignedContract(Reservation reservation, Rental rental) {
        if (contractRepository == null) return;
        Instant now = Instant.now();
        String unitCode = rental.getStorageUnit() == null ? "UNIT" : rental.getStorageUnit().getCode();
        var contract = contractRepository.findByRental_Id(rental.getId())
            .orElseGet(com.storagehub.domain.model.Contract::new);
        contract.setRental(rental);
        contract.setStatus(com.storagehub.domain.model.ContractStatus.SIGNED);
        if (contract.getContractNumber() == null) {
            contract.setContractNumber("HD-" + unitCode + "-" + rental.getId().toString().substring(0, 8).toUpperCase());
        }
        if (contract.getSentAt() == null) contract.setSentAt(now);
        contract.setSignedAt(now);
        contract.setSignedBy(reservation.getCustomer() == null ? "Customer" : reservation.getCustomer().getFullName());
        contract.setExpiresAt(rental.getContractEndDate().atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
        contractRepository.save(contract);
    }

    private void activateReceivedRental(Rental rental) {
        if (rental.getStatus() == RentalStatus.setup
            && (rental.getSetupEndsAt() == null || !rental.getSetupEndsAt().isAfter(Instant.now()))) {
            rental.setStatus(RentalStatus.active);
            rentalRepository.saveAndFlush(rental);
        }
    }

    private void requireReceiptRentalStatus(Rental rental) {
        if (rental.getStatus() == RentalStatus.completed) {
            throw ApiExceptions.conflict("Hồ sơ thuê đã kết thúc. Mở Hồ sơ thuê để xem biên bản và quyết toán.");
        }
        if (rental.getStatus() != RentalStatus.setup && rental.getStatus() != RentalStatus.active) {
            throw ApiExceptions.conflict("Hồ sơ thuê đang trong quy trình trả kho. Mở Hồ sơ thuê để theo dõi tiến độ trả kho.");
        }
    }

    /** Receipt activates a verified handover, including early inventory, without changing paid dates. */
    private void requireReceiptTimeline(Reservation reservation,CheckIn checkIn,Rental rental,ReservationPricingSnapshot snapshot) {
        requireReceiptRentalStatus(rental);
        String missing="Thiếu hoặc lệch chứng cứ bàn giao/thời gian chuẩn bị, cần đối soát trước khi xác nhận nhận kho.";
        if(rental.getId()==null||rental.getReservation()==null||rental.getCustomer()==null||rental.getFacility()==null||rental.getStorageUnit()==null
            ||checkIn.getRental()==null||!java.util.Objects.equals(checkIn.getRental().getId(),rental.getId())
            ||!java.util.Objects.equals(rental.getReservation().getId(),reservation.getId())
            ||!java.util.Objects.equals(rental.getCustomer().getId(),reservation.getCustomer().getId())
            ||!java.util.Objects.equals(rental.getFacility().getId(),reservation.getFacility().getId())
            ||!java.util.Objects.equals(rental.getStorageUnit().getId(),reservation.getAssignedUnit().getId())
            ||rental.getActualReturnedAt()!=null||rental.getCompletedAt()!=null
            ||rental.getSetupStartedAt()==null||rental.getSetupEndsAt()==null||rental.getTermsEffectiveAt()==null
            ||rental.getStartDate()==null||rental.getContractEndDate()==null||rental.getInventoryVerifiedAt()==null
            ||checkIn.getContractSignedAt()==null||checkIn.getPaymentCollectedAt()==null||checkIn.getInventoryVerifiedAt()==null)
            throw ApiExceptions.conflict(missing);
        int setupDays=ReservationSnapshotView.requireSetupDays(reservation,snapshot);
        try {
            var now=Instant.now();var zone=com.storagehub.time.BusinessTime.ZONE;
            var paidStart=rental.getSetupStartedAt().atZone(zone).toLocalDate().plusDays(setupDays);
            if(!rental.getSetupStartedAt().equals(checkIn.getSetupStartedAt())
                ||!rental.getSetupStartedAt().equals(rental.getTermsEffectiveAt())
                ||!rental.getSetupEndsAt().equals(checkIn.getSetupDueAt())
                ||!rental.getInventoryVerifiedAt().equals(checkIn.getInventoryVerifiedAt())
                ||!rental.getStartDate().equals(paidStart)
                ||!rental.getSetupEndsAt().equals(paidStart.atStartOfDay(zone).toInstant())
                ||!rental.getContractEndDate().equals(paidStart.plusMonths(snapshot.getRentalMonths()))
                ||checkIn.getContractSignedAt().isAfter(rental.getSetupStartedAt())
                ||checkIn.getPaymentCollectedAt().isAfter(rental.getSetupStartedAt())
                ||rental.getSetupStartedAt().isAfter(now)
                ||rental.getInventoryVerifiedAt().isBefore(rental.getSetupStartedAt())
                ||rental.getInventoryVerifiedAt().isAfter(now))
                throw ApiExceptions.conflict(missing);
        } catch(java.time.DateTimeException invalid){throw ApiExceptions.conflict(missing);}
    }

    @Transactional
    public ReservationResponse setCheckInAppointment(
        ActorPrincipal actor,
        UUID reservationId,
        CheckInAppointmentRequest request
    ) {
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        if (reservation.getStatus() != ReservationStatus.AWAITING_EMAIL
            && reservation.getStatus() != ReservationStatus.AWAITING_REVIEW
            && reservation.getStatus() != ReservationStatus.AWAITING_PAYMENT
            && reservation.getStatus() != ReservationStatus.PAYMENT_GRACE
            && reservation.getStatus() != ReservationStatus.PAYMENT_REVIEW
            && reservation.getStatus() != ReservationStatus.CONFIRMED
            && reservation.getStatus() != ReservationStatus.UNIT_RESERVED
            && reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN) {
            throw ApiExceptions.conflict("Reservation can no longer change its check-in appointment");
        }
        ReservationCheckInPolicy.Window checkInWindow = ReservationCheckInPolicy.plannedWindow(
            reservation.getCreatedAt(),
            reservation.getGoodsReviewStatus() != com.storagehub.domain.model.GoodsReviewStatus.NOT_REQUIRED
        );
        if (!checkInWindow.contains(request.appointmentAt())) {
            throw ApiExceptions.validation(
                "Check-in appointment must be within the permitted seven-day window", null
            );
        }
        Instant previousAppointmentAt = reservation.getAppointmentAt();
        LocalDate previousStartDate = reservation.getStartDate();
        LocalDate previousEndDate = reservation.getEndDate();
        reservation.setAppointmentAt(request.appointmentAt());
        var pricing = snapshotRepository.findByReservation_Id(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        if (pricing.getRentalMonths() > 0) {
            var estimatedStart = request.appointmentAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toLocalDate().plusDays(pricing.getFreeSetupDays());
            LocalDate estimatedEnd = estimatedStart.plusMonths(pricing.getRentalMonths());
            boolean datesChanged = !estimatedStart.equals(previousStartDate) || !estimatedEnd.equals(previousEndDate);
            if (datesChanged && reservation.getAssignedUnit() == null && reservationCapacityService != null) {
                boolean holdsCapacity = reservation.getStatus() == ReservationStatus.CONFIRMED
                    || reservation.getStatus() == ReservationStatus.AWAITING_PAYMENT
                    || reservation.getStatus() == ReservationStatus.PAYMENT_GRACE
                    || reservation.getStatus() == ReservationStatus.PAYMENT_REVIEW
                    || reservation.getStatus() == ReservationStatus.AWAITING_EMAIL
                    || reservation.getStatus() == ReservationStatus.AWAITING_REVIEW;
                reservationCapacityService.lockAndRequireAvailableCapacityForReschedule(
                    reservation.getFacility().getId(), reservation.getUnitType().getId(),
                    estimatedStart, estimatedEnd, previousStartDate, previousEndDate, holdsCapacity
                );
            }
            reservation.setStartDate(estimatedStart);
            reservation.setEndDate(estimatedEnd);
        }
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservationId).orElse(null);
        if (checkIn != null && checkIn.getStatus() != CheckInStatus.scheduled) {
            throw ApiExceptions.conflict("Check-in has already started or been closed");
        }
        if (checkIn != null) {
            checkIn.setScheduledAt(request.appointmentAt());
            checkInRepository.saveAndFlush(checkIn);
        }
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        Map<String, Object> before = new HashMap<>();
        before.put("appointmentAt", previousAppointmentAt);
        auditLogService.recordMutation(
            saved.getCustomer(), "CUSTOMER_CHECKIN_APPOINTMENT_SET", "Reservation", saved.getId(),
            saved.getFacility().getId(), before, Map.of("appointmentAt", saved.getAppointmentAt())
        );
        notificationService.notifyStaffOfCheckInAppointmentChange(saved, previousAppointmentAt);
        return toSummaryResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CheckInDocumentResponse> listCheckInDocuments(ActorPrincipal actor, UUID reservationId) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (checkIn == null) {
            return List.of();
        }
        Map<String, Kind> documentKinds = checkInDocumentKinds(checkIn, reservation.getId());
        boolean hasContractReference = clean(checkIn.getContractEvidenceReference()) != null;
        return fileAssetRepository.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc("CHECK_IN", checkIn.getId())
            .stream()
            .filter(asset -> asset.getStatus() == FileAssetStatus.ACTIVE)
            .map(asset -> {
                Kind kind = documentKinds.get(asset.getId().toString());
                if (kind == null) {
                    boolean legacyContract = !hasContractReference && asset.getOriginalName() != null
                        && asset.getOriginalName().toLowerCase(Locale.ROOT).startsWith("hop-dong-da-ky-");
                    kind = legacyContract ? Kind.SIGNED_CONTRACT : Kind.OTHER;
                }
                return new CheckInDocumentResponse(
                    asset.getId(), asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes(),
                    asset.getChecksumSha256(), asset.getEntityType(), asset.getEntityId(), asset.getStatus(),
                    asset.getCreatedAt(), kind
                );
            })
            .sorted(Comparator.comparing(CheckInDocumentResponse::kind))
            .toList();
    }

    private Map<String, Kind> checkInDocumentKinds(CheckIn checkIn, UUID reservationId) {
        Map<String, Kind> kinds = new HashMap<>();
        tagCheckInDocument(kinds, checkIn.getContractEvidenceReference(), Kind.SIGNED_CONTRACT);
        for (Payment payment : paymentRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservationId)) {
            if (payment.getPurpose() == PaymentType.RENTAL_BALANCE && payment.getStatus() == PaymentStatus.PAID
                && "MANUAL".equals(payment.getGatewayProvider())) {
                UUID receiptId = paymentProvenance.manualReceiptFileId(payment);
                if (receiptId != null) {
                    kinds.putIfAbsent(receiptId.toString(), Kind.PAYMENT_RECEIPT);
                }
            }
        }

        String readinessNote = checkIn.getReadinessNote();
        String evidenceMarker = " | Bằng chứng: ";
        if (readinessNote != null && readinessNote.startsWith("Tình trạng kho trống: ")) {
            int customerNoteIndex = readinessNote.indexOf(" | Ghi chú khách: ");
            String handoverNote = customerNoteIndex < 0 ? readinessNote : readinessNote.substring(0, customerNoteIndex);
            int markerIndex = handoverNote.lastIndexOf(evidenceMarker);
            if (markerIndex >= 0) {
                for (String reference : handoverNote.substring(markerIndex + evidenceMarker.length()).split(",")) {
                    tagCheckInDocument(kinds, reference, Kind.HANDOVER_EVIDENCE);
                }
            }
        }

        if (checkIn.getStatus() == CheckInStatus.completed && clean(checkIn.getChecklistJson()) != null) {
            try {
                var references = objectMapper.readTree(checkIn.getChecklistJson()).path("evidenceReferences");
                if (references.isArray()) {
                    for (var reference : references) {
                        tagCheckInDocument(kinds, reference.asText(), Kind.INVENTORY_EVIDENCE);
                    }
                }
            } catch (JsonProcessingException ignored) {
                // Legacy checklist data may be unreadable. Its files can still be shown without a category.
            }
        }
        return kinds;
    }

    private static void tagCheckInDocument(Map<String, Kind> kinds, String reference, Kind kind) {
        String id = clean(reference);
        if (id != null) {
            kinds.putIfAbsent(id.toLowerCase(Locale.ROOT), kind);
        }
    }

    @Transactional
    public ReservationDetailResponse cancel(
        ActorPrincipal actor,
        UUID reservationId,
        CancelReservationRequest request
    ) {
        Reservation reservation = reservationRepository
            .findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        ReservationStatus previousStatus = reservation.getStatus();
        Rental handedOver = rentalRepository == null ? null : rentalRepository.findByReservation_Id(reservationId)
            .filter(r -> r.getSetupStartedAt() != null && r.getStatus() != RentalStatus.completed).orElse(null);
        if (handedOver != null) {
            var service = returnSettlementServices == null ? null : returnSettlementServices.getIfAvailable();
            if (service == null && returnSettlements != null) service = returnSettlements.getIfAvailable();
            if (service == null) throw ApiExceptions.conflict("Chưa có luồng trả kho. Vui lòng liên hệ cơ sở để xử lý.");
            service.createCustomerReturnRequest(actor, handedOver.getId(), new com.storagehub.api.returns.CustomerCreateReturnRequest(
                java.time.LocalDate.now(com.storagehub.time.BusinessTime.ZONE), request == null ? "Khách yêu cầu trả kho sau bàn giao" : request.getReason()));
            return toDetailResponse(reservation);
        }
        if (!canBeCancelled(previousStatus)) throw ApiExceptions.conflict("Reservation cannot be cancelled when status is " + previousStatus);

        List<Payment> payments = paymentRepository.findReservationPaymentsForUpdate(
            reservationId, PaymentType.RESERVATION_DEPOSIT
        );
        if (payments.stream().anyMatch(this::isPaymentBeingFinalized)) {
            throw ApiExceptions.conflict(
                "Reservation cannot be cancelled while payment is processing"
            );
        }

        payments.stream()
            .filter(payment -> payment.getStatus() == PaymentStatus.PENDING
                || payment.getStatus() == PaymentStatus.NOT_RECEIVED)
            .forEach(payment -> payment.setStatus(PaymentStatus.CANCELLED));

        if (previousStatus == ReservationStatus.PAYMENT_REVIEW && paymentComplaintRepository != null) {
            paymentComplaintRepository.findByReservation_Id(reservationId).ifPresent(complaint -> {
                if (complaint.getStatus() == PaymentComplaintStatus.PENDING
                    || complaint.getStatus() == PaymentComplaintStatus.REVIEW_OVERDUE) {
                    complaint.setStatus(PaymentComplaintStatus.WITHDRAWN);
                    complaint.setReviewedAt(Instant.now());
                    complaint.setDecisionReason("Customer cancelled reservation before complaint decision");
                    paymentComplaintRepository.saveAndFlush(complaint);
                }
            });
        }

        Payment paidDeposit = payments.stream()
            .filter(payment -> payment.getStatus() == PaymentStatus.PAID)
            .findFirst()
            .orElse(null);
        if (paidDeposit != null) {
            BigDecimal deposit = paidDeposit.getAmount().setScale(2, RoundingMode.HALF_UP);
            reservation.setCancellationType(ReservationCancellationType.BEFORE_CHECKIN_EARLY_FEE);
            reservation.setCancellationChargeAmount(deposit);
            reservation.setCancellationRefundAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        // Assignment/unit release still belongs to the operations flow; this service
        // records the cancellation settlement but does not mutate storage inventory.
        Instant cancelledAt = Instant.now();
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(cancelledAt);
        reservation.setArchivedAt(cancelledAt);
        reservation.setCancelReason(request.getReason().trim());
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        boolean assignedUnitReleased = releaseAssignedUnitAfterCancellation(saved, cancelledAt);
        ReservationDetailResponse response = toDetailResponse(saved);

        auditLogService.recordMutation(
            saved.getCustomer(), "RESERVATION_CANCELLED", "Reservation", saved.getId(),
            saved.getFacility().getId(),
            Map.of("status", previousStatus),
            Map.of("status", saved.getStatus(), "reason", saved.getCancelReason())
        );
        notificationService.createNotification(
            saved.getCustomer().getId(), NotificationType.RESERVATION,
            "Đơn giữ kho đã được hủy",
            "Đơn " + saved.getReservationCode() + " đã được hủy theo yêu cầu của bạn.",
            saved.getId()
        );
        notificationService.notifyStaffOfReservationCancellation(saved, assignedUnitReleased);
        return response;
    }

    @Transactional
    public void deleteHistory(ActorPrincipal actor, UUID reservationId) {
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        ReservationStatus status = reservation.getStatus();
        if (status != ReservationStatus.EXPIRED
            && status != ReservationStatus.COMPLETED
            && status != ReservationStatus.CANCELLED
            && status != ReservationStatus.REJECTED) {
            throw ApiExceptions.conflict("Only ended reservations can be removed from active history");
        }
        if (reservation.getArchivedAt() == null) {
            reservation.setArchivedAt(Instant.now());
            reservationRepository.saveAndFlush(reservation);
            auditLogService.recordMutation(
                reservation.getCustomer(), "RESERVATION_HISTORY_DELETED", "Reservation", reservation.getId(),
                reservation.getFacility().getId(), null, Map.of("status", status)
            );
        }
    }

    private boolean releaseAssignedUnitAfterCancellation(Reservation reservation, Instant cancelledAt) {
        if (assignmentRepository == null || storageUnitRepository == null || checkInRepository == null
            || rentalRepository == null || reservation.getAssignedUnit() == null) {
            return false;
        }
        var assignment = assignmentRepository
            .findByReservation_IdAndStatus(reservation.getId(), UnitAssignmentStatus.ACTIVE)
            .orElse(null);
        if (assignment == null) {
            return false;
        }
        var unit = assignment.getStorageUnit();
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (checkIn != null && checkIn.getStatus() == CheckInStatus.completed) {
            throw ApiExceptions.conflict("The assigned unit cannot be released because check-in is completed");
        }
        if (rentalRepository.existsActiveRental(reservation.getId(), unit.getId(), RentalStatus.active)) {
            throw ApiExceptions.conflict("The assigned unit cannot be released because an active rental exists");
        }
        if (unit.getStatus() != StorageUnitStatus.reserved
            && unit.getStatus() != StorageUnitStatus.assigned) {
            throw ApiExceptions.conflict("Storage unit is not in a releasable state");
        }

        StorageUnitStatus previousStatus = unit.getStatus();
        unit.setStatus(StorageUnitStatus.available);
        unit.setAvailableFrom(cancelledAt);
        unit.setLastReleasedAt(cancelledAt);
        assignment.setStatus(UnitAssignmentStatus.CANCELLED);
        assignment.setCancelledBy(reservation.getCustomer());
        assignment.setCancelledAt(cancelledAt);
        assignment.setCancelReason(reservation.getCancelReason());
        assignment.setReleaseDisposition(UnitReleaseDisposition.AVAILABLE);
        assignment.setPreviousStorageUnitStatus(previousStatus);
        reservation.setAssignedUnit(null);
        if (checkIn != null && checkIn.getStatus() == CheckInStatus.scheduled) {
            checkIn.setStatus(CheckInStatus.cancelled);
            checkInRepository.saveAndFlush(checkIn);
        }
        storageUnitRepository.saveAndFlush(unit);
        assignmentRepository.saveAndFlush(assignment);
        reservationRepository.saveAndFlush(reservation);
        return true;
    }

    private Reservation findOwnedReservation(ActorPrincipal actor, UUID reservationId) {
        return reservationRepository.findByIdAndCustomer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
    }

    private boolean canBeCancelled(ReservationStatus status) {
        return status == ReservationStatus.AWAITING_EMAIL
            || status == ReservationStatus.AWAITING_REVIEW
            || status == ReservationStatus.AWAITING_PAYMENT
            || status == ReservationStatus.PAYMENT_GRACE
            || status == ReservationStatus.PAYMENT_REVIEW
            || status == ReservationStatus.CONFIRMED
            || status == ReservationStatus.UNIT_RESERVED
            || status == ReservationStatus.READY_FOR_CHECKIN;
    }

    private boolean isPaymentBeingFinalized(Payment payment) {
        return payment.getStatus() == PaymentStatus.PROCESSING;
    }

    private PageRequest createPageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation(
                "page must be >= 0 and size must be between 1 and 100", null
            );
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private ReservationResponse toSummaryResponse(Reservation reservation) {
        ReservationPricingSnapshot snapshot = snapshotRepository
            .findByReservation_Id(reservation.getId())
            .orElse(null);
        if(snapshot==null)return ReservationSnapshotView.missingResponse(reservation);
        ReservationResponse response = new ReservationResponse(
            reservation.getId(), reservation.getReservationCode(), reservation.getSourceQuote()==null?null:reservation.getSourceQuote().getId(),
            reservation.getFacility().getId(), reservation.getUnitType().getId(), reservation.getStatus(),
            reservation.getGoodsReviewStatus(), reservation.getCompatibilityResult(),
            reservation.getStartDate(), reservation.getEndDate(), snapshot.getNetRentalAmount(),
            snapshot.getReservationDepositAmount(), snapshot.getSecurityDepositAmount(),
            snapshot.getRemainingRentalAmount(), snapshot.getDueAtCheckIn(),
            snapshot.getTotalInitialObligation(), reservation.getTotalGoodsVolumeM3(),
            reservation.getTotalGoodsWeightKg(), reservation.getHoldExpiresAt(), reservation.getCreatedAt(),
            snapshot.getPricingPackageCode(), snapshot.getRentalMonths(), snapshot.getGrossRentalAmount(),
            snapshot.getDiscountRate(), snapshot.getDiscountAmount(), reservation.getAppointmentAt()
        );
        response.setMonthlyPrice(snapshot.getMonthlyPrice());
        response.setUnitTypeName(ReservationSnapshotView.unitTypeName(reservation));
        ReservationSnapshotView.describe(response,reservation,snapshot);
        response.setRentalPeriodConfirmed(reservation.getStatus() == ReservationStatus.COMPLETED
            || checkInRepository != null && checkInRepository.findByReservation_Id(reservation.getId())
                .map(checkIn -> checkIn.getSetupStartedAt() != null).orElse(false));
        response.setSetupGoodsRevisionUsedAt(reservation.getSetupGoodsRevisionUsedAt());
        if (rentalRepository != null) {
            rentalRepository.findByReservation_Id(reservation.getId()).ifPresent(rental -> {
                response.setSetupExitReviewRequired(rental.isSetupExitReviewRequired());
                if (rental.getSetupStartedAt() != null && rental.getStatus() != RentalStatus.completed) {
                    response.setSetupReturnRentalId(rental.getId());
                    if (setupReturns != null) setupReturns.findByRental_Id(rental.getId()).stream()
                        .filter(rc -> rc.getStatus() != com.storagehub.domain.model.ReturnCaseStatus.completed).findFirst()
                        .ifPresent(rc -> response.setSetupReturnCaseId(rc.getId()));
                }
            });
        }
        response.setVoucherCode(snapshot.getVoucherCode());
        response.setVoucherDiscountAmount(snapshot.getVoucherDiscountAmount());
        response.setCustomerName(reservation.getCustomer().getFullName());
        response.setCustomerEmail(reservation.getCustomer().getEmail());
        response.setCustomerPhone(reservation.getCustomerPhone() != null
            ? reservation.getCustomerPhone() : reservation.getCustomer().getPhone());
        response.setCustomerAddress(reservation.getCustomerAddress() != null
            ? reservation.getCustomerAddress() : reservation.getCustomer().getPermanentAddress());
        response.setIdentityDocumentNumber(reservation.getIdentityDocumentNumber());
        if (reservation.getAssignedUnit() != null) {
            response.setAssignedUnitId(reservation.getAssignedUnit().getId());
            response.setAssignedUnitCode(reservation.getAssignedUnit().getCode());
            response.setAssignedUnitStatus(reservation.getAssignedUnit().getStatus().name());
        }
        return response;
    }

    private ReservationDetailResponse toDetailResponse(Reservation reservation) {
        List<ReservationGoodsItemResponse> goodsItems = goodsItemRepository
            .findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
            .stream()
            .map(this::toGoodsItemResponse)
            .toList();

        ReservationDetailResponse response = new ReservationDetailResponse();
        response.setReservation(toSummaryResponse(reservation));
        response.setGoodsCondition(reservation.getGoodsCondition());
        response.setNotes(reservation.getNotes());
        response.setPaymentExpiresAt(reservation.getPaymentExpiresAt());
        response.setComplaintExpiresAt(reservation.getComplaintExpiresAt());
        response.setArchivedAt(reservation.getArchivedAt());
        response.setConfirmedAt(reservation.getConfirmedAt());
        response.setCancelledAt(reservation.getCancelledAt());
        response.setCancelReason(reservation.getCancelReason());
        response.setCancellationType(reservation.getCancellationType());
        response.setCancellationRefundAmount(reservation.getCancellationRefundAmount());
        response.setCancellationChargeAmount(reservation.getCancellationChargeAmount());
        response.setGoodsItems(goodsItems);
        response.setPayments(paymentRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
            .stream()
            .map(payment -> toPaymentResponse(payment, reservation.getStatus()))
            .toList());
        checkInRepository.findByReservation_Id(reservation.getId()).ifPresent(checkIn ->
            response.setCheckInStatus(checkIn.getStatus().name()));
        rentalRepository.findByReservation_Id(reservation.getId()).ifPresent(rental ->
            response.setRentalStatus(rental.getStatus().name()));
        return response;
    }

    private CustomerPaymentReceiptResponse toPaymentResponse(Payment payment, ReservationStatus reservationStatus) {
        return new CustomerPaymentReceiptResponse(
            payment.getId(), payment.getReservation().getId(), payment.getGatewayIntentId(),
            payment.getGatewayTransactionNo(), payment.getAmount(), payment.getCurrency(),
            payment.getRefundedAmount(), payment.getStatus(), payment.getPurpose(), reservationStatus,
            payment.getGatewayResponseCode(), payment.getGatewayTransactionStatus(),
            payment.getGatewayPayDate(), payment.getGatewayBankCode(),
            payment.getStatus() == PaymentStatus.PAID ? "Payment was recorded successfully" : "Payment is awaiting confirmation",
            payment.getProcessedAt(), payment.getPaidAt(), payment.getLastReconciledAt(),
            payment.getInitiatedBy().getFullName(), payment.getCreatedAt(), payment.getReservation().getReservationCode(),
            payment.getInvoiceNumber(), payment.getInvoiceStatus(), payment.getInvoiceIssuedAt()
        );
    }

    private ReservationGoodsItemResponse toGoodsItemResponse(ReservationGoodsItem item) {
        ReservationGoodsItemResponse response = new ReservationGoodsItemResponse();
        response.setId(item.getId());
        response.setGoodsCode(item.getGoodsCode());
        response.setCatalogCategoryId(item.getCatalogCategoryId());
        response.setCatalogMaterialId(item.getCatalogMaterialId());
        response.setCategoryCode(item.getCategoryCode());
        response.setCategoryName(GoodsCatalogService.historicalCategoryName(item));
        response.setMaterialCode(item.getMaterialCode());
        response.setCategory(item.getCategory());
        response.setCustomGoodsName(item.getCustomGoodsName());
        response.setMaterialName(item.getMaterialName());
        response.setCustomMaterial(item.getCustomMaterial());
        response.setDescription(item.getDescription());
        response.setQuantity(item.getQuantity());
        response.setLengthCm(item.getLengthCm());
        response.setWidthCm(item.getWidthCm());
        response.setHeightCm(item.getHeightCm());
        response.setWeightPerItemKg(item.getWeightKg());
        response.setFragile(item.isFragile());
        response.setCustomerNote(item.getCustomerNote());
        response.setRequiresStaffReview(item.isRequiresStaffReview());
        response.setReviewStatus(item.getReviewStatus());
        response.setReviewNote(item.getReviewNote());
        if (fileAssetRepository != null) {
            response.setImages(fileAssetRepository
                .findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc("RESERVATION_GOODS_ITEM", item.getId())
                .stream()
                .filter(asset -> asset.getStatus() == FileAssetStatus.ACTIVE)
                .map(asset -> new FileAssetResponse(
                    asset.getId(), asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes(),
                    asset.getChecksumSha256(), asset.getEntityType(), asset.getEntityId(),
                    asset.getStatus(), asset.getCreatedAt()
                ))
                .toList());
        }
        return response;
    }
}
