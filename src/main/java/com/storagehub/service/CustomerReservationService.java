package com.storagehub.service;

import com.storagehub.api.reservation.CancelReservationRequest;
import com.storagehub.api.reservation.CheckInAppointmentRequest;
import com.storagehub.api.reservation.ReservationDetailResponse;
import com.storagehub.api.reservation.ReservationGoodsItemResponse;
import com.storagehub.api.reservation.ReservationResponse;
import com.storagehub.api.reservation.UpdateSetupGoodsRequest;
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
import com.storagehub.domain.model.FileAssetStatus;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.ledger.LedgerStore;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
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
    private com.storagehub.service.rental.period.ConfirmedRentalPeriods confirmedRentalPeriods;

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
            throw ApiExceptions.forbidden("Only customers can update setup goods");
        }
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        Rental rental = rentalRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Setup rental was not found"));
        Instant now = Instant.now();
        if (rental.getSetupStartedAt() == null || !rental.getSetupStartedAt().isBefore(now)
            || rental.getSetupEndsAt() == null || !rental.getSetupEndsAt().isAfter(now)) {
            throw ApiExceptions.conflict("Goods can only be amended during the setup period");
        }
        if (rental.getInventoryVerifiedAt() != null || reservation.getSetupGoodsRevisionUsedAt() != null) {
            throw ApiExceptions.conflict("The setup goods declaration has already been locked or amended");
        }

        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        List<ReservationGoodsItem> replacement = new java.util.ArrayList<>();
        for (var input : request.goodsItems()) {
            BigDecimal itemVolume = input.getLengthCm().multiply(input.getWidthCm())
                .multiply(input.getHeightCm()).divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
            totalVolume = totalVolume.add(itemVolume.multiply(BigDecimal.valueOf(input.getQuantity())));
            totalWeight = totalWeight.add(input.getWeightPerItemKg().multiply(BigDecimal.valueOf(input.getQuantity())));
            ReservationGoodsItem item = new ReservationGoodsItem();
            item.setReservation(reservation);
            item.setCategory(input.getCategory());
            item.setCustomGoodsName(clean(input.getCustomGoodsName()));
            item.setMaterialName(clean(input.getMaterialName()));
            item.setCustomMaterial(clean(input.getCustomMaterial()));
            item.setDescription(clean(input.getDescription()));
            item.setQuantity(input.getQuantity());
            item.setLengthCm(input.getLengthCm());
            item.setWidthCm(input.getWidthCm());
            item.setHeightCm(input.getHeightCm());
            item.setWeightKg(input.getWeightPerItemKg());
            item.setFragile(input.isFragile());
            item.setCustomerNote(clean(input.getCustomerNote()));
            item.setRequiresStaffReview(input.getCategory() == GoodsCategory.OTHER);
            item.setReviewStatus(item.isRequiresStaffReview() ? GoodsReviewStatus.PENDING : GoodsReviewStatus.NOT_REQUIRED);
            replacement.add(item);
        }
        if (reservation.getUnitType().getVolumeM3() != null
            && totalVolume.compareTo(reservation.getUnitType().getVolumeM3()) > 0) {
            throw ApiExceptions.conflict("Goods volume exceeds the storage unit capacity");
        }
        if (reservation.getUnitType().getMaxLoadKg() != null
            && totalWeight.compareTo(reservation.getUnitType().getMaxLoadKg()) > 0) {
            throw ApiExceptions.conflict("Goods weight exceeds the storage unit capacity");
        }
        goodsItemRepository.deleteAll(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservationId));
        goodsItemRepository.saveAll(replacement);
        reservation.setTotalGoodsVolumeM3(totalVolume.setScale(6, RoundingMode.HALF_UP));
        reservation.setTotalGoodsWeightKg(totalWeight.setScale(2, RoundingMode.HALF_UP));
        reservation.setGoodsCondition(clean(request.goodsCondition()));
        reservation.setSetupGoodsRevisionUsedAt(now);
        reservationRepository.saveAndFlush(reservation);
        auditLogService.recordMutation(
            reservation.getCustomer(), "SETUP_GOODS_DECLARATION_AMENDED", "Reservation", reservation.getId(),
            reservation.getFacility().getId(), null,
            Map.of("revisionUsedAt", now, "itemCount", replacement.size())
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Khách đã cập nhật khai báo hàng trong setup",
            "Đơn " + reservation.getReservationCode() + " đã dùng lần sửa khai báo duy nhất trước kiểm kê.",
            reservation.getId()
        );
        return toDetailResponse(reservation);
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

        // A completed reservation is already finalized. Treat a repeated customer
        // confirmation as an idempotent read so it cannot create another rental,
        // reopen a ledger account, or emit duplicate handover side effects.
        if (reservation.getStatus() == ReservationStatus.COMPLETED) {
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
        if (checkIn.getStatus() != CheckInStatus.completed) {
            throw ApiExceptions.conflict("Storage unit handover is not completed");
        }

        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        if (ledgerStores != null && ledgerStores.getIfAvailable() != null) {
            com.storagehub.service.ledger.FinancialContractSpecifications.validateInitial(snapshot);
        }
        var existingRental = rentalRepository.findByReservation_Id(reservationId);
        Rental rental = existingRental.orElseGet(() -> {
            Rental created = new Rental();
            created.setCustomer(reservation.getCustomer());
            created.setFacility(reservation.getFacility());
            created.setStorageUnit(reservation.getAssignedUnit());
            created.setReservation(reservation);
            created.setStatus(RentalStatus.active);
            created.setStartDate(reservation.getStartDate());
            created.setContractEndDate(reservation.getEndDate());
            created.setMonthlyPrice(snapshot.getMonthlyPrice());
            return rentalRepository.saveAndFlush(created);
        });
        if (existingRental.isEmpty() && contractRepository != null) {
            Instant contractNow = Instant.now();
            String unitCode = rental.getStorageUnit() != null ? rental.getStorageUnit().getCode() : "UNIT";
            String contractNo = "HD-" + unitCode + "-" + rental.getId().toString().substring(0, 8).toUpperCase();
            com.storagehub.domain.model.Contract contract = new com.storagehub.domain.model.Contract();
            contract.setRental(rental);
            contract.setStatus(com.storagehub.domain.model.ContractStatus.SIGNED);
            contract.setContractNumber(contractNo);
            contract.setSentAt(contractNow);
            contract.setSignedAt(contractNow);
            contract.setSignedBy(reservation.getCustomer() != null ? reservation.getCustomer().getFullName() : "Customer");
            if (rental.getContractEndDate() != null) {
                contract.setExpiresAt(rental.getContractEndDate().atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
            }
            contractRepository.save(contract);
        }

        // Open the subledger only for a Rental created by this confirmed receipt event.
        // The ledger writer joins this method's transaction; existing/legacy Rentals are not backfilled.
        if (existingRental.isEmpty() && ledgerStores != null) {
            LedgerStore ledger = ledgerStores.getIfAvailable();
            if (ledger != null) {
                com.storagehub.service.ledger.FinancialContractSpecifications.initial(rental, snapshot);
                ledger.open(rental.getId(), rental.getCustomer().getId(), rental.getFacility().getId());
            }
        }

        reservation.getAssignedUnit().setStatus(StorageUnitStatus.occupied);
        storageUnitRepository.saveAndFlush(reservation.getAssignedUnit());
        checkIn.setRental(rental);
        checkInRepository.saveAndFlush(checkIn);
        if (accessCredentialService != null) {
            accessCredentialService.attachToRental(reservation, rental);
        }
        ReservationStatus previousStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.COMPLETED);
        Reservation saved = reservationRepository.saveAndFlush(reservation);

        if(existingRental.isEmpty() && confirmedRentalPeriods!=null) confirmedRentalPeriods.receipt(rental);
        auditLogService.recordMutation(
            saved.getCustomer(), "CUSTOMER_RECEIPT_CONFIRMED", "Reservation", saved.getId(),
            saved.getFacility().getId(),
            Map.of("status", previousStatus),
            Map.of("status", saved.getStatus(), "rentalId", rental.getId(), "rentalPeriodEvidence",
                com.storagehub.service.rental.period.RentalPeriodEvidence.receipt(rental, existingRental.isEmpty()))
        );
        notificationService.createNotification(
            saved.getCustomer().getId(), NotificationType.CHECKIN,
            "Đã xác nhận nhận kho",
            "Đơn " + saved.getReservationCode() + " đã được xác nhận nhận kho và tạo hồ sơ thuê.",
            saved.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            saved.getFacility().getId(), NotificationType.CHECKIN,
            "Khách hàng đã xác nhận nhận kho",
            "Khách hàng đã xác nhận nhận kho cho đơn " + saved.getReservationCode()
                + ". Hồ sơ thuê đã được tạo.",
            saved.getId()
        );
        return toDetailResponse(saved);
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
        FacilityAppointmentHours.validate(request.appointmentAt(), reservation.getFacility().getAccessHours());
        Instant previousAppointmentAt = reservation.getAppointmentAt();
        reservation.setAppointmentAt(request.appointmentAt());
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
    public List<FileAssetResponse> listCheckInDocuments(ActorPrincipal actor, UUID reservationId) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (checkIn == null) {
            return List.of();
        }
        return fileAssetRepository.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc("CHECK_IN", checkIn.getId())
            .stream()
            .filter(asset -> asset.getStatus() == FileAssetStatus.ACTIVE)
            .sorted((left, right) -> {
                boolean leftContract = left.getOriginalName() != null
                    && left.getOriginalName().toLowerCase().startsWith("hop-dong-da-ky-");
                boolean rightContract = right.getOriginalName() != null
                    && right.getOriginalName().toLowerCase().startsWith("hop-dong-da-ky-");
                if (leftContract == rightContract) {
                    return 0;
                }
                return leftContract ? -1 : 1;
            })
            .map(asset -> new FileAssetResponse(
                asset.getId(), asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes(),
                asset.getChecksumSha256(), asset.getEntityType(), asset.getEntityId(), asset.getStatus(), asset.getCreatedAt()
            ))
            .toList();
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
        if (!canBeCancelled(previousStatus)) {
            throw ApiExceptions.conflict(
                "Reservation cannot be cancelled when status is " + previousStatus
            );
        }

        Rental setupRental = rentalRepository == null ? null
            : rentalRepository.findByReservation_Id(reservationId)
                .filter(rental -> rental.getStatus() == RentalStatus.setup)
                .orElse(null);
        if (setupRental != null) {
            if (!setupRental.isSetupExitReviewRequired()) {
                setupRental.setSetupExitReviewRequired(true);
                rentalRepository.saveAndFlush(setupRental);
                String handling = setupRental.getMoveInCompletedAt() == null
                    ? "Hàng chưa được xác nhận đã chuyển vào: xử lý như hủy sau check-in."
                    : "Hàng đã chuyển vào: phải dọn kho và kiểm tra trả kho trước khi quyết toán.";
                notificationService.createBusinessNotifications(
                    NotificationType.RESERVATION, "Yêu cầu quyết định hoàn tiền trong thời gian setup",
                    "Đơn " + reservation.getReservationCode() + ". " + handling
                        + " Không tự động hoàn tiền giữ chỗ hoặc tiền thuê.", reservation.getId()
                );
                notificationService.createNotification(
                    reservation.getCustomer().getId(), NotificationType.RESERVATION,
                    "Yêu cầu hủy/trả kho đang chờ quyết định",
                    "Business sẽ quyết định số tiền hoàn. Hệ thống chưa tự động hoàn bất kỳ khoản nào.",
                    reservation.getId()
                );
                auditLogService.recordMutation(
                    reservation.getCustomer(), "SETUP_EXIT_REVIEW_REQUESTED", "Rental", setupRental.getId(),
                    reservation.getFacility().getId(), null,
                    Map.of("setupExitReviewRequired", true, "goodsMovedIn", setupRental.getMoveInCompletedAt() != null)
                );
            }
            return toDetailResponse(reservation);
        }

        List<Payment> payments = paymentRepository.findReservationPaymentsForUpdate(
            reservationId, PaymentType.RESERVATION_DEPOSIT
        );
        if (payments.stream().anyMatch(this::isPaymentBeingFinalized)) {
            throw ApiExceptions.conflict(
                "Reservation cannot be cancelled while payment is processing"
            );
        }

        payments.stream()
            .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
            .forEach(payment -> payment.setStatus(PaymentStatus.CANCELLED));

        // A paid deposit is intentionally non-refundable when the customer cancels
        // before check-in. Assignment/unit release belongs to the operations flow;
        // this service does not clear assignedUnit or change StorageUnit status.
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
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        ReservationResponse response = new ReservationResponse(
            reservation.getId(), reservation.getReservationCode(), reservation.getSourceQuote().getId(),
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
        response.setFreeSetupDays(snapshot.getFreeSetupDays());
        response.setSetupGoodsRevisionUsedAt(reservation.getSetupGoodsRevisionUsedAt());
        if (rentalRepository != null) {
            response.setSetupExitReviewRequired(rentalRepository.findByReservation_Id(reservation.getId())
                .map(Rental::isSetupExitReviewRequired).orElse(false));
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
        response.setGoodsItems(goodsItems);
        response.setPayments(paymentRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
            .stream()
            .map(payment -> toPaymentResponse(payment, reservation.getStatus()))
            .toList());
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
            payment.getInitiatedBy().getFullName(), payment.getCreatedAt(), payment.getReservation().getReservationCode()
        );
    }

    private ReservationGoodsItemResponse toGoodsItemResponse(ReservationGoodsItem item) {
        ReservationGoodsItemResponse response = new ReservationGoodsItemResponse();
        response.setId(item.getId());
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
