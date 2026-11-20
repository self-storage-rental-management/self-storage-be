package com.storagehub.service;

import com.storagehub.api.configuration.UnitTypePricingRequest;
import com.storagehub.api.facility.FacilityResponse;
import com.storagehub.api.facility.FacilityLifecycleSummary;
import com.storagehub.api.facility.ManagerUnitTypeRequest;
import com.storagehub.api.facility.UnitTypeResponse;
import com.storagehub.api.facility.UpdateFacilityRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityLifecycleChangeRequest;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.RefundRequestStatus;
import com.storagehub.domain.model.RefundStatus;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FacilityLifecycleService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.email.CustomerLifecycleEmailService customerEmails;
    private final FacilityRepository facilities;
    private final UnitTypeRepository unitTypes;
    private final StorageUnitRepository units;
    private final ReservationCapacityService reservationCapacity;
    private final RentalPackagePolicyRepository packages;
    private final FacilityScopeService scopes;
    private final AdminAuthorizationService authorization;
    private final AuditLogService audit;
    private final UserFacilityScopeRepository userFacilityScopes;
    private final com.storagehub.domain.repo.ReservationRepository reservations;
    private final NotificationService notifications;
    private final com.storagehub.domain.repo.UnitTypeChangeRequestRepository changes;
    private final com.storagehub.domain.repo.FacilityLifecycleChangeRequestRepository facilityLifecycleChanges;
    private final com.storagehub.domain.repo.ContractRepository contracts;
    private final com.storagehub.domain.repo.PaymentRepository payments;
    private final com.storagehub.domain.repo.RefundRepository refunds;
    private final com.storagehub.domain.repo.RefundRequestRepository refundRequests;
    private final com.storagehub.domain.repo.UserRepository users;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.storagehub.domain.repo.GoodsTypeCatalogRepository goodsTypes;
    private final com.storagehub.domain.repo.GoodsMaterialCatalogRepository goodsMaterials;
    private final RackLayoutService rackLayouts;

    private static final List<ReservationStatus> PENDING_HOLD_STATUSES = List.of(
        ReservationStatus.AWAITING_EMAIL,
        ReservationStatus.AWAITING_REVIEW,
        ReservationStatus.AWAITING_PAYMENT,
        ReservationStatus.PAYMENT_GRACE,
        ReservationStatus.PAYMENT_REVIEW
    );

    private static final List<ReservationStatus> PROCESSING_RESERVATION_STATUSES = List.of(
        ReservationStatus.CONFIRMED,
        ReservationStatus.UNIT_RESERVED,
        ReservationStatus.READY_FOR_CHECKIN,
        ReservationStatus.AWAITING_CUSTOMER_RECEIPT,
        ReservationStatus.PREPARING
    );

    private static final List<PaymentStatus> OUTSTANDING_PAYMENT_STATUSES = List.of(
        PaymentStatus.PENDING,
        PaymentStatus.PROCESSING,
        PaymentStatus.NOT_RECEIVED,
        PaymentStatus.FAILED
    );

    private static final List<RefundStatus> OUTSTANDING_REFUND_STATUSES = List.of(
        RefundStatus.PENDING,
        RefundStatus.PROCESSING,
        RefundStatus.FAILED
    );

    private static final List<RefundRequestStatus> OUTSTANDING_REFUND_REQUEST_STATUSES = List.of(
        RefundRequestStatus.REQUESTED,
        RefundRequestStatus.PENDING_ESCALATION,
        RefundRequestStatus.APPROVED,
        RefundRequestStatus.PROCESSING,
        RefundRequestStatus.FAILED
    );

    private static byte[] bytes(UUID id) {
        if (id == null) return null;
        return java.nio.ByteBuffer.allocate(16)
            .putLong(id.getMostSignificantBits())
            .putLong(id.getLeastSignificantBits())
            .array();
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void deleteFacility(ActorPrincipal actor, UUID facilityId) {
        archiveFacility(actor, facilityId);
    }

    /**
     * New archive path. It never removes the facility or its history and does
     * not archive unit types behind the scenes. Unit types remain available to
     * the restore and pricing workflow.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public FacilityResponse archiveFacility(ActorPrincipal actor, UUID facilityId) {
        requireBusiness(actor);
        Facility facility = lockFacility(facilityId);
        requireArchiveReady(facility);
        FacilityResponse before = facilityResponse(facility);
        facility.setStatus(FacilityStatus.inactive);
        FacilityResponse after = facilityResponse(facilities.saveAndFlush(facility));
        audit.recordMutation("FACILITY_ARCHIVED", "Facility", facilityId, facilityId, before, after);
        return after;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public FacilityResponse updateFacility(ActorPrincipal actor, UUID facilityId, UpdateFacilityRequest request) {
        requireBusiness(actor);
        Facility facility = lockFacility(facilityId);
        FacilityResponse before = facilityResponse(facility);
        facility.setName(request.name().trim());
        facility.setAddress(request.address().trim());
        facility.setCity(request.city().trim());
        facility.setPhone(clean(request.phone()));
        facility.setAccessHours(clean(request.accessHours()));
        facility.setImageUrl(clean(request.imageUrl()));
        if (request.climateControlled() != null) facility.setClimateControlled(request.climateControlled());
        facility.setSecurityDescription(clean(request.securityDescription()));
        if (request.status() != null && request.status() != facility.getStatus()) {
            if (request.status() == FacilityStatus.active) {
                validateAndActivate(facility);
            } else if (request.status() == FacilityStatus.inactive) {
                requireArchiveReady(facility);
                facility.setStatus(FacilityStatus.inactive);
            } else {
                facility.setStatus(request.status());
            }
        }
        Facility saved = facilities.save(facility);
        FacilityResponse after = facilityResponse(saved);
        audit.recordMutation("FACILITY_UPDATED", "Facility", saved.getId(), saved.getId(), before, after);
        if (before.status() != after.status() || !java.util.Objects.equals(before.accessHours(), after.accessHours())
            || !java.util.Objects.equals(before.address(), after.address()) || !java.util.Objects.equals(before.phone(), after.phone())) customerEmails.facilityChanged(saved.getId(), "details:" + UUID.randomUUID(),
            "Cập nhật hoạt động cơ sở", "Cơ sở vừa cập nhật thông tin hoạt động, giờ ra vào hoặc trạng thái phục vụ. Vui lòng xem thông tin mới và liên hệ cơ sở nếu ảnh hưởng đến việc sử dụng kho.");
        return after;
    }

    @Transactional(readOnly = true)
    public com.storagehub.api.facility.FacilityLifecycleSummary getFacilityLifecycleSummaryForManager(
        ActorPrincipal actor, UUID facilityId
    ) {
        requireManager(actor, facilityId);
        return lifecycleSummary(facility(facilityId));
    }

    @Transactional(readOnly = true)
    public com.storagehub.api.facility.FacilityLifecycleSummary getFacilityLifecycleSummaryForBusiness(
        ActorPrincipal actor, UUID facilityId
    ) {
        requireBusiness(actor);
        return lifecycleSummary(facility(facilityId));
    }

    @Transactional
    public com.storagehub.api.facility.FacilityLifecycleChangeResponse requestFacilityLifecycleChange(
        ActorPrincipal actor, UUID facilityId, com.storagehub.api.facility.FacilityLifecycleRequestBody request
    ) {
        requireManager(actor, facilityId);
        Facility facility = facility(facilityId);
        validateLifecycleTarget(request.requestedStatus());
        if (facility.getStatus() == FacilityStatus.inactive) {
            throw ApiExceptions.conflict("Cơ sở đã ngừng hoạt động. BO cần dùng chức năng khôi phục trước khi tạo yêu cầu mới.");
        }
        if (facilityLifecycleChanges.existsByFacilityIdAndStatus(facilityId, "PENDING")) {
            throw ApiExceptions.conflict("Cơ sở đang có một yêu cầu chờ BO xử lý.");
        }

        FacilityLifecycleChangeRequest change = new FacilityLifecycleChangeRequest();
        change.setFacilityId(facilityId);
        change.setFacilityName(facility.getName());
        change.setRequestedBy(actor.userId());
        change.setRequesterName(users.findById(actor.userId())
            .map(com.storagehub.domain.model.User::getFullName).orElse("Manager"));
        change.setRequestedStatus(request.requestedStatus());
        change.setReason(clean(request.reason()));
        FacilityLifecycleChangeRequest saved = facilityLifecycleChanges.saveAndFlush(change);
        audit.recordMutation("FACILITY_LIFECYCLE_REQUESTED", "FacilityLifecycleChangeRequest", saved.getId(), facilityId, null, lifecycleChangeResponse(saved));
        return lifecycleChangeResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<com.storagehub.api.facility.FacilityLifecycleChangeResponse> listFacilityLifecycleChanges(
        ActorPrincipal actor
    ) {
        requireBusiness(actor);
        return facilityLifecycleChanges.findTop100ByOrderByCreatedAtDesc().stream()
            .map(this::lifecycleChangeResponse).toList();
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public com.storagehub.api.facility.FacilityLifecycleChangeResponse reviewFacilityLifecycleChange(
        ActorPrincipal actor, UUID requestId, com.storagehub.api.facility.FacilityLifecycleReviewRequest decision
    ) {
        requireBusiness(actor);
        FacilityLifecycleChangeRequest change = facilityLifecycleChanges.findLockedById(requestId)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu thay đổi trạng thái cơ sở."));
        if (!"PENDING".equals(change.getStatus())) {
            throw ApiExceptions.conflict("Yêu cầu này đã được xử lý.");
        }
        if (!decision.approved() && clean(decision.reason()) == null) {
            throw ApiExceptions.conflict("Vui lòng nhập lý do từ chối.");
        }

        if (decision.approved()) {
            Facility facility = lockFacility(change.getFacilityId());
            if (change.getRequestedStatus() == FacilityStatus.inactive) {
                requireArchiveReady(facility);
            } else if (facility.getStatus() == FacilityStatus.inactive) {
                throw ApiExceptions.conflict("Cơ sở đã được lưu trữ. Hãy xử lý lại đề nghị này.");
            }
            FacilityResponse before = facilityResponse(facility);
            facility.setStatus(change.getRequestedStatus());
            FacilityResponse after = facilityResponse(facilities.saveAndFlush(facility));
            audit.recordMutation("FACILITY_LIFECYCLE_APPROVED", "Facility", facility.getId(), facility.getId(), before, after);
        }
        change.setStatus(decision.approved() ? "APPROVED" : "REJECTED");
        change.setReviewedBy(actor.userId());
        change.setReviewedAt(java.time.Instant.now());
        change.setRejectionReason(decision.approved() ? null : clean(decision.reason()));
        FacilityLifecycleChangeRequest saved = facilityLifecycleChanges.save(change);
        audit.recordMutation("FACILITY_LIFECYCLE_REVIEWED", "FacilityLifecycleChangeRequest", requestId, change.getFacilityId(), null, lifecycleChangeResponse(saved));
        return lifecycleChangeResponse(saved);
    }

    @Transactional
    public FacilityResponse restoreFacility(ActorPrincipal actor, UUID facilityId) {
        requireBusiness(actor);
        Facility facility = lockFacility(facilityId);
        if (facility.getStatus() != FacilityStatus.inactive && facility.getStatus() != FacilityStatus.maintenance) {
            throw ApiExceptions.conflict("Cơ sở hiện không ở trạng thái có thể khôi phục.");
        }

        FacilityResponse before = facilityResponse(facility);
        List<UnitType> facilityTypes = unitTypes.findByFacility_IdOrderByCodeAsc(facilityId);
        if (facility.getStatus() == FacilityStatus.maintenance) {
            boolean hasPublishedType = facilityTypes.stream().anyMatch(type -> type.getStatus() == UnitTypeStatus.active
                && type.getMonthlyPrice() != null && type.getMonthlyPrice().signum() > 0);
            if (!hasPublishedType) {
                throw ApiExceptions.conflict("Cơ sở cần có ít nhất một loại kho đã công bố và có giá thuê để mở lại nhận đơn.");
            }
            facility.setStatus(FacilityStatus.active);
        } else {
            List<UnitType> archivedTypes = facilityTypes.stream().filter(type -> type.getStatus() == UnitTypeStatus.archived).toList();
            if (archivedTypes.isEmpty()) {
                validateAndActivate(facility);
            } else {
                // Legacy facility archive records need a fresh BO pricing review.
                archivedTypes.forEach(type -> {
                    type.setMonthlyPrice(BigDecimal.ZERO);
                    type.setSecurityDepositAmount(BigDecimal.ZERO);
                    type.setStatus(UnitTypeStatus.awaiting_pricing);
                });
                unitTypes.saveAll(archivedTypes);
                facility.setStatus(FacilityStatus.setup);
            }
        }
        FacilityResponse after = facilityResponse(facilities.saveAndFlush(facility));
        audit.recordMutation("FACILITY_RESTORED", "Facility", facilityId, facilityId, before, after);
        return after;
    }

    @Transactional
    public UnitTypeResponse createUnitType(ActorPrincipal actor, UUID facilityId, ManagerUnitTypeRequest request) {
        requireManager(actor, facilityId);
        validateOperationalPairs(request);
        Facility facility = facility(facilityId);
        if (facility.getStatus() == FacilityStatus.inactive) {
            throw ApiExceptions.conflict("Inactive facility cannot receive unit types");
        }
        String code = generateUnitTypeCode(facilityId);
        UnitType type = new UnitType();
        type.setFacility(facility);
        applyTechnical(type, request);
        rackLayouts.confirmNewType(actor, type, request.rackLayout());
        type.setCode(code);
        type.setMonthlyPrice(BigDecimal.ZERO);
        type.setSecurityDepositAmount(BigDecimal.ZERO);
        type.setStatus(UnitTypeStatus.awaiting_pricing);
        UnitType saved = unitTypes.saveAndFlush(type);
        createPhysicalUnits(saved, request.physicalUnitCount(), request.floor(), request.zone());
        UnitTypeResponse response = unitTypeResponse(saved);
        audit.recordMutation("UNIT_TYPE_CREATED_BY_MANAGER", "UnitType", saved.getId(), facilityId, null, response);
        return response;
    }

    @Transactional
    public UnitTypeResponse updateUnitType(ActorPrincipal actor, UUID unitTypeId, ManagerUnitTypeRequest request) {
        UnitType type = unitTypes.findByIdForUpdate(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UUID facilityId = type.getFacility().getId();
        requireManager(actor, facilityId);
        validateOperationalPairs(request);
        if (type.getStatus() == UnitTypeStatus.archived) {
            throw ApiExceptions.conflict("Loại kho đã được lưu trữ, không thể chỉnh sửa.");
        }
        if (request.code() != null && !request.code().isBlank()
            && !type.getCode().equalsIgnoreCase(request.code().trim())) {
            throw ApiExceptions.conflict("Không thể đổi mã loại kho đã tạo.");
        }
        boolean technicalChanged = technicalChanged(type, request);
        boolean requiresRepricing = technicalChanged || goodsScopeChanged(type, request);
        if (changes.existsByUnitTypeIdAndStatus(unitTypeId, "PENDING")) {
            throw ApiExceptions.conflict("Loại kho đang có yêu cầu chờ BO duyệt. Vui lòng đợi xử lý trước khi gửi thay đổi mới.");
        }
        validateSharedRackDimensions(type, request);
        ManagerUnitTypeRequest before = technicalSnapshot(type);
        ManagerUnitTypeRequest after = normalizedChange(request);
        long currentCount = units.countByFacility_IdAndUnitType_IdAndStatusIn(
            facilityId, unitTypeId, StorageUnitStatus.operationalStatuses());
        int requestedCount = request.physicalUnitCount();
        boolean quantityChanged = requestedCount > 0 && requestedCount != currentCount;

        if (quantityChanged) {
            if (requestedCount > currentCount) {
                int toAdd = (int) (requestedCount - currentCount);
                createPhysicalUnits(type, toAdd, request.floor(), request.zone());
            } else {
                int toRemove = (int) (currentCount - requestedCount);
                var palletHolds = reservationCapacity.livePalletHoldUnitIds(unitTypeId);
                var availableUnits = units.findByFacility_Id(facilityId).stream()
                    .filter(u -> u.getUnitType().getId().equals(unitTypeId))
                    .filter(u -> u.getStatus() == StorageUnitStatus.available)
                    .filter(u -> !palletHolds.contains(u.getId()))
                    .sorted((a, b) -> b.getCode().compareTo(a.getCode()))
                    .toList();
                if (availableUnits.size() < toRemove) {
                    long inUse = currentCount - availableUnits.size();
                    throw ApiExceptions.conflict("Không thể giảm xuống " + requestedCount + " gian kho vì hiện có " + inUse + " gian đang được thuê hoặc giữ chỗ.");
                }
                var unitsToArchive = availableUnits.subList(0, toRemove);
                unitsToArchive.forEach(unit -> {
                    unit.setStatus(StorageUnitStatus.archived);
                    unit.setAvailableFrom(null);
                });
                units.saveAll(unitsToArchive);
                units.flush();
            }
        }

        boolean metadataChanged = !java.util.Objects.equals(type.getName(), after.name())
            || !java.util.Objects.equals(type.getDescription(), after.description())
            || !java.util.Objects.equals(type.getImageUrl(), after.imageUrl())
            || !java.util.Objects.equals(type.getDetailImageUrl(), after.detailImageUrl());

        if (!requiresRepricing && !metadataChanged && !quantityChanged) {
            throw ApiExceptions.conflict("Thông tin loại kho chưa có thay đổi.");
        }

        if (requiresRepricing || metadataChanged) {
            var change = new com.storagehub.domain.model.UnitTypeChangeRequest();
            change.setUnitTypeId(unitTypeId);
            change.setFacilityId(facilityId);
            change.setFacilityName(type.getFacility().getName());
            change.setUnitTypeCode(type.getCode());
            change.setRequestedBy(actor.userId());
            change.setRequesterName(users.findById(actor.userId()).map(com.storagehub.domain.model.User::getFullName).orElse("Manager"));
            change.setBeforeJson(json(before));
            change.setAfterJson(json(after));
            change.setRequiresRepricing(requiresRepricing);
            change = changes.saveAndFlush(change);
            audit.recordMutation("UNIT_TYPE_CHANGE_REQUESTED", "UnitTypeChangeRequest", change.getId(), facilityId, before, after);
            notifications.notifyBusinessOfUnitTypeReview(type, change.getId());
        } else if (quantityChanged) {
            audit.recordMutation("UNIT_TYPE_QUANTITY_UPDATED", "UnitType", type.getId(), facilityId, before, unitTypeResponse(type));
        }
        return unitTypeResponse(type);
    }

    private ManagerUnitTypeRequest technicalSnapshot(UnitType t) {
        return new ManagerUnitTypeRequest(t.getCode(), t.getName(), t.getDescription(), t.getLengthM(), t.getWidthM(),
            t.getHeightM(), t.getMaxLoadKg(), t.getRackCount(), t.getRackLengthM(), t.getRackWidthM(), t.getRackHeightM(),
            t.getAisleWidthM(), t.getTrolleyType(), t.getSmallBoxCapacity() == null ? 0 : t.getSmallBoxCapacity(),
            t.getLargeBoxCapacity() == null ? 0 : t.getLargeBoxCapacity(), t.getImageUrl(), 0, null, null, t.getDetailImageUrl(),
            goodsScope(t).categoryIds(), goodsScope(t).materialIds(), null, t.getMaxStoredLoadKg(),
            t.getStorageAisleWidthM(), t.getGateWidthM(), t.getGateHeightM(),
            t.getLowestObstructionHeightM(), t.getOverheadClearanceM(),
            t.getLengthM().movePointRight(2), t.getWidthM().movePointRight(2), t.getHeightM().movePointRight(2),
            t.getStorageAisleWidthM() == null ? null : t.getStorageAisleWidthM().movePointRight(2),
            t.getGateWidthM() == null ? null : t.getGateWidthM().movePointRight(2),
            t.getGateHeightM() == null ? null : t.getGateHeightM().movePointRight(2),
            t.getLowestObstructionHeightM() == null ? null : t.getLowestObstructionHeightM().movePointRight(2),
            t.getOverheadClearanceM() == null ? null : t.getOverheadClearanceM().movePointRight(2),
            t.getRackLengthM().movePointRight(2), t.getRackWidthM().movePointRight(2),
            t.getRackHeightM().movePointRight(2), t.getAisleWidthM().movePointRight(2));
    }

    private ManagerUnitTypeRequest normalizedChange(ManagerUnitTypeRequest r) {
        return new ManagerUnitTypeRequest(clean(r.code()), r.name().trim(), clean(r.description()), r.lengthM(),
            r.widthM(), r.heightM(), r.maxLoadKg(), r.rackCount(), r.rackLengthM(), r.rackWidthM(), r.rackHeightM(),
            r.aisleWidthM(), r.trolleyType().trim(), r.smallBoxCapacity(), r.largeBoxCapacity(), clean(r.imageUrl()), 0, null, null, clean(r.detailImageUrl()),
            distinct(r.acceptedGoodsCategoryIds()), distinct(r.acceptedGoodsMaterialIds()), r.rackLayout(), r.maxStoredLoadKg(),
            r.storageAisleWidthM(), r.gateWidthM(), r.gateHeightM(), r.lowestObstructionHeightM(), r.overheadClearanceM(),
            r.lengthCm(), r.widthCm(), r.heightCm(), r.storageAisleWidthCm(), r.gateWidthCm(), r.gateHeightCm(),
            r.lowestObstructionHeightCm(), r.overheadClearanceCm(), r.rackLengthCm(), r.rackWidthCm(),
            r.rackHeightCm(), r.aisleWidthCm());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot serialize review", e); }
    }

    private ManagerUnitTypeRequest readChange(String value) {
        try { return objectMapper.readValue(value, ManagerUnitTypeRequest.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot read review", e); }
    }

    private com.storagehub.api.facility.UnitTypeChangeResponse changeResponse(com.storagehub.domain.model.UnitTypeChangeRequest r) {
        UnitType current = unitTypes.findById(r.getUnitTypeId()).orElse(null);
        return new com.storagehub.api.facility.UnitTypeChangeResponse(r.getId(), r.getUnitTypeId(), r.getFacilityId(),
            r.getFacilityName(), r.getUnitTypeCode(), r.getRequestedBy(), r.getRequesterName(), r.getCreatedAt(), r.getStatus(),
            readChange(r.getBeforeJson()), readChange(r.getAfterJson()), r.getReviewedBy(), r.getReviewedAt(), r.getRejectionReason(), r.isRequiresRepricing(),
            current == null ? null : current.getMonthlyPrice(), current == null ? null : current.getSecurityDepositAmount());
    }

    @Transactional(readOnly = true)
    public List<com.storagehub.api.facility.UnitTypeChangeResponse> listUnitTypeChanges(ActorPrincipal actor) {
        requireBusiness(actor);
        return changes.findTop100ByOrderByCreatedAtDesc().stream().map(this::changeResponse).toList();
    }

    @Transactional(readOnly = true)
    public com.storagehub.api.facility.UnitTypeChangeResponse getUnitTypeChange(ActorPrincipal actor, UUID id) {
        requireBusiness(actor);
        return changeResponse(changes.findById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu duyệt.")));
    }

    @Transactional
    public com.storagehub.api.facility.UnitTypeChangeResponse reviewUnitTypeChange(ActorPrincipal actor, UUID id, boolean approved, String reason,
                                                                                  BigDecimal monthlyPrice, BigDecimal securityDepositAmount) {
        requireBusiness(actor);
        var change = changes.findLockedById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu duyệt."));
        if (!"PENDING".equals(change.getStatus())) throw ApiExceptions.conflict("Yêu cầu này đã được xử lý.");
        if (!approved && clean(reason) == null) throw ApiExceptions.conflict("Vui lòng nhập lý do từ chối.");
        UnitType type = unitTypes.findByIdForUpdate(change.getUnitTypeId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy loại kho."));
        if (approved) {
            ManagerUnitTypeRequest after = readChange(change.getAfterJson());
            if (!json(technicalSnapshot(type)).equals(json(readChange(change.getBeforeJson())))) {
                throw ApiExceptions.conflict("Thông tin gốc đã thay đổi. Hãy từ chối và yêu cầu Manager gửi lại.");
            }
            UnitTypeResponse before = unitTypeResponse(type);
            if (change.isRequiresRepricing() && (monthlyPrice == null || monthlyPrice.signum() <= 0
                || securityDepositAmount == null || securityDepositAmount.signum() < 0)) {
                throw ApiExceptions.validation("Thay đổi này ảnh hưởng giá. BO phải xác nhận giá thuê và tiền đảm bảo trước khi duyệt.", null);
            }
            validateOperationalPairs(after);
            validateSharedRackDimensions(type, after);
            applyTechnical(type, after);
            if (after.rackLayout() != null) {
                rackLayouts.applyReviewedLayout(type, after.rackLayout());
            } else if (type.getRackLayoutJson() != null) {
                rackLayouts.applyRackSummary(type, rackLayouts.layoutForPacking(type, type.getRackLayoutJson()));
            }
            if (monthlyPrice != null) type.setMonthlyPrice(monthlyPrice);
            if (securityDepositAmount != null) type.setSecurityDepositAmount(securityDepositAmount);
            type.setPublishedRevision(Math.max(1, type.getPublishedRevision() + 1));
            unitTypes.save(type);
            audit.recordMutation("UNIT_TYPE_CHANGE_APPROVED", "UnitType", type.getId(), change.getFacilityId(), before, unitTypeResponse(type));
        }
        change.setStatus(approved ? "APPROVED" : "REJECTED");
        change.setReviewedBy(actor.userId());
        change.setReviewedAt(java.time.Instant.now());
        change.setRejectionReason(approved ? null : clean(reason));
        changes.save(change);
        audit.recordMutation("UNIT_TYPE_CHANGE_REVIEWED", "UnitTypeChangeRequest", id, change.getFacilityId(), null, changeResponse(change));
        notifications.notifyUnitTypeReviewResult(change);
        return changeResponse(change);
    }

    /** Compatibility overload retained for existing callers and tests. */
    public com.storagehub.api.facility.UnitTypeChangeResponse reviewUnitTypeChange(ActorPrincipal actor, UUID id, boolean approved, String reason) {
        return reviewUnitTypeChange(actor, id, approved, reason, null, null);
    }

    private boolean different(BigDecimal before, BigDecimal after) {
        return before == null || after == null ? before != after : before.compareTo(after) != 0;
    }

    private boolean optionalDifferent(BigDecimal before, BigDecimal after) {
        return after != null && different(before, after);
    }

    private void validateSharedRackDimensions(UnitType type, ManagerUnitTypeRequest request) {
        if (request.rackLayout() != null) return;
        if (type.getRackLayoutJson() == null || !(different(type.getLengthM(), request.lengthM())
            || different(type.getWidthM(), request.widthM()) || different(type.getHeightM(), request.heightM())
            || different(type.getMaxLoadKg(), request.maxLoadKg())
            || optionalDifferent(type.getMaxStoredLoadKg(), request.maxStoredLoadKg())
            || optionalDifferent(type.getLowestObstructionHeightM(), request.lowestObstructionHeightM())
            || optionalDifferent(type.getOverheadClearanceM(), request.overheadClearanceM()))) return;
        rackLayouts.requireEditable(type);
        UnitType proposed = new UnitType();
        proposed.setLengthM(request.lengthM());
        proposed.setWidthM(request.widthM());
        proposed.setHeightM(request.heightM());
        proposed.setMaxLoadKg(request.maxLoadKg());
        proposed.setMaxStoredLoadKg(request.maxStoredLoadKg() == null
            ? type.getMaxStoredLoadKg() : request.maxStoredLoadKg());
        proposed.setLowestObstructionHeightM(request.lowestObstructionHeightM() == null
            ? type.getLowestObstructionHeightM() : request.lowestObstructionHeightM());
        proposed.setOverheadClearanceM(request.overheadClearanceM() == null
            ? type.getOverheadClearanceM() : request.overheadClearanceM());
        rackLayouts.layoutForPacking(proposed, type.getRackLayoutJson());
    }

    private boolean technicalChanged(UnitType t, ManagerUnitTypeRequest r) {
        return different(t.getLengthM(), r.lengthM()) || different(t.getWidthM(), r.widthM())
            || different(t.getHeightM(), r.heightM()) || different(t.getMaxLoadKg(), r.maxLoadKg())
            || optionalDifferent(t.getMaxStoredLoadKg(), r.maxStoredLoadKg())
            || optionalDifferent(t.getStorageAisleWidthM(), r.storageAisleWidthM())
            || optionalDifferent(t.getGateWidthM(), r.gateWidthM())
            || optionalDifferent(t.getGateHeightM(), r.gateHeightM())
            || optionalDifferent(t.getLowestObstructionHeightM(), r.lowestObstructionHeightM())
            || optionalDifferent(t.getOverheadClearanceM(), r.overheadClearanceM())
            || t.getRackCount() != r.rackCount() || different(t.getRackLengthM(), r.rackLengthM())
            || different(t.getRackWidthM(), r.rackWidthM()) || different(t.getRackHeightM(), r.rackHeightM())
            || different(t.getAisleWidthM(), r.aisleWidthM())
            || r.rackLayout() != null
            || !java.util.Objects.equals(t.getTrolleyType(), r.trolleyType().trim())
            || !java.util.Objects.equals(t.getSmallBoxCapacity(), r.smallBoxCapacity())
            || !java.util.Objects.equals(t.getLargeBoxCapacity(), r.largeBoxCapacity());
    }

    private boolean goodsScopeChanged(UnitType type, ManagerUnitTypeRequest request) {
        GoodsScope current = goodsScope(type);
        return !current.categoryIds().equals(distinct(request.acceptedGoodsCategoryIds()))
            || !current.materialIds().equals(distinct(request.acceptedGoodsMaterialIds()));
    }

    private List<UUID> distinct(List<UUID> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    private record GoodsScope(List<UUID> categoryIds, List<UUID> materialIds) {}

    private GoodsScope goodsScope(UnitType type) {
        try {
            return objectMapper.readValue(type.getAcceptedGoodsJson(), GoodsScope.class);
        } catch (Exception ignored) {
            return new GoodsScope(List.of(), List.of());
        }
    }

    private String approvedGoodsJson(ManagerUnitTypeRequest request) {
        List<UUID> categoryIds = distinct(request.acceptedGoodsCategoryIds());
        List<UUID> materialIds = distinct(request.acceptedGoodsMaterialIds());
        // Backward-compatible requests created before scoped goods existed mean "all active".
        if (categoryIds.isEmpty()) categoryIds = goodsTypes.findAllByStatusOrderByCodeAsc(com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)
            .stream().map(com.storagehub.domain.model.GoodsTypeCatalog::getId).sorted().toList();
        List<UUID> selectedCategoryIds = categoryIds;
        if (materialIds.isEmpty()) materialIds = goodsMaterials.findAll().stream()
            .filter(value -> value.getStatus() == com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)
            .filter(value -> selectedCategoryIds.contains(value.getGoodsType().getId()))
            .map(com.storagehub.domain.model.GoodsMaterialCatalog::getId).sorted().toList();
        var categories = goodsTypes.findAllById(categoryIds);
        var materials = goodsMaterials.findAllById(materialIds);
        if (categories.size() != categoryIds.size() || categories.stream().anyMatch(v -> v.getStatus() != com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)) {
            throw ApiExceptions.validation("Có loại hàng không tồn tại hoặc đã ngừng áp dụng.", null);
        }
        if (materials.size() != materialIds.size() || materials.stream().anyMatch(v -> v.getStatus() != com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)) {
            throw ApiExceptions.validation("Có vật liệu không tồn tại hoặc đã ngừng áp dụng.", null);
        }
        if (materials.stream().anyMatch(v -> !selectedCategoryIds.contains(v.getGoodsType().getId()))) {
            throw ApiExceptions.validation("Vật liệu được chọn phải thuộc một loại hàng được áp dụng.", null);
        }
        return json(new GoodsScope(categoryIds, materialIds));
    }

    @Transactional
    public void deleteUnitType(ActorPrincipal actor, UUID unitTypeId) {
        UnitType type = unitTypes.findByIdForUpdate(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UUID facilityId = type.getFacility().getId();
        requireManager(actor, facilityId);
        if (units.countByFacility_IdAndUnitType_IdAndStatusIn(facilityId, unitTypeId, List.of(
            StorageUnitStatus.occupied, StorageUnitStatus.reserved,
            StorageUnitStatus.assigned, StorageUnitStatus.held)) > 0) {
            throw ApiExceptions.conflict("Không thể lưu trữ loại kho đang có gian kho đang thuê hoặc giữ chỗ.");
        }
        if (changes.existsByUnitTypeIdAndStatus(unitTypeId, "PENDING")) {
            throw ApiExceptions.conflict("Không thể xóa loại kho đang chờ BO duyệt thay đổi.");
        }
        if (type.getStatus() == UnitTypeStatus.archived) {
            return;
        }

        List<StorageUnit> physicalUnits = units.findByUnitTypeIdForUpdate(unitTypeId);
        if (physicalUnits == null) physicalUnits = List.of();
        long occupiedCount = physicalUnits.stream()
            .filter(unit -> unit.getStatus() == StorageUnitStatus.occupied)
            .count();
        if (occupiedCount > 0) {
            throw ApiExceptions.conflict(
                "Không thể lưu trữ loại kho vì còn " + occupiedCount + " gian đang có khách sử dụng.");
        }

        long heldOrAssignedCount = physicalUnits.stream()
            .filter(unit -> unit.getStatus() == StorageUnitStatus.reserved
                || unit.getStatus() == StorageUnitStatus.held
                || unit.getStatus() == StorageUnitStatus.assigned)
            .count();
        if (heldOrAssignedCount > 0) {
            throw ApiExceptions.conflict(
                "Không thể lưu trữ loại kho vì còn " + heldOrAssignedCount + " gian đang được giữ hoặc đã phân cho khách.");
        }

        if (reservationCapacity.hasLiveCapacityHold(unitTypeId)) {
            throw ApiExceptions.conflict(
                "Không thể lưu trữ loại kho vì còn đơn đặt kho đang xử lý, kể cả đơn chưa được phân gian.");
        }

        UnitTypeResponse before = unitTypeResponse(type);
        type.setStatus(UnitTypeStatus.archived);
        UnitType saved = unitTypes.saveAndFlush(type);
        audit.recordMutation("UNIT_TYPE_ARCHIVED_BY_MANAGER", "UnitType", unitTypeId, facilityId,
            before, unitTypeResponse(saved));
    }

    @Transactional
    public UnitTypeResponse restoreUnitType(ActorPrincipal actor, UUID unitTypeId) {
        UnitType type = unitTypes.findByIdForUpdate(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UUID facilityId = type.getFacility().getId();
        requireManager(actor, facilityId);
        if (type.getStatus() != UnitTypeStatus.archived) {
            throw ApiExceptions.conflict("Loại kho chưa ở trạng thái lưu trữ nên không cần khôi phục.");
        }
        if (changes.existsByUnitTypeIdAndStatus(unitTypeId, "PENDING")) {
            throw ApiExceptions.conflict("Loại kho đang có yêu cầu BO duyệt thay đổi. Vui lòng đợi xử lý trước khi khôi phục.");
        }

        // Recheck the stored operational inputs and the generated rack layout
        // before making the type visible to the pricing and publishing flow.
        validateOperationalPairs(technicalSnapshot(type));
        try {
            if (type.getRackLayoutJson() == null || type.getRackLayoutJson().isBlank()) {
                rackLayouts.confirmNewType(actor, type, null);
            } else {
                try {
                    if (rackLayouts.layoutForPacking(type, type.getRackLayoutJson()) == null) {
                        throw ApiExceptions.validation("Cấu hình kệ pallet chưa đầy đủ.", List.of(
                            "Loại kho chưa có bố trí kệ pallet để khôi phục."
                        ));
                    }
                } catch (com.storagehub.common.api.ApiException exception) {
                    if (!isBuiltInDemoUnitType(type) || exception.getStatus() != org.springframework.http.HttpStatus.BAD_REQUEST) {
                        throw exception;
                    }
                    // Built-in demo types may still carry a legacy snapshot
                    // whose rack gaps no longer match the current warehouse
                    // aisle. Regenerate the same number of racks using the
                    // current backend-owned rules before restoring the type.
                    rackLayouts.confirmNewType(actor, type, null);
                }
            }
        } catch (IllegalStateException exception) {
            throw ApiExceptions.validation("Cấu hình kệ pallet chưa hợp lệ.", List.of(
                "Không thể đọc bố trí kệ đã lưu. Vui lòng kiểm tra lại cấu hình trước khi khôi phục."
            ));
        }

        UnitTypeResponse before = unitTypeResponse(type);
        // Restoring starts a new pricing cycle. Do not expose or reuse the
        // price and deposit from the previously archived publication.
        type.setMonthlyPrice(BigDecimal.ZERO);
        type.setSecurityDepositAmount(BigDecimal.ZERO);
        type.setStatus(UnitTypeStatus.awaiting_pricing);
        UnitType saved = unitTypes.saveAndFlush(type);
        audit.recordMutation("UNIT_TYPE_RESTORED_BY_MANAGER", "UnitType", unitTypeId, facilityId,
            before, unitTypeResponse(saved));
        return unitTypeResponse(saved);
    }

    private boolean isBuiltInDemoUnitType(UnitType type) {
        return type.getCode() != null
            && java.util.Set.of("S", "M", "L", "XL").contains(type.getCode().trim().toUpperCase());
    }

    @Transactional
    public UnitTypeResponse priceUnitType(ActorPrincipal actor, UUID unitTypeId, UnitTypePricingRequest request) {
        requireBusiness(actor);
        UnitType type = unitTypes.findById(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        if (type.getStatus() == UnitTypeStatus.archived) {
            throw ApiExceptions.conflict("Loại kho đã được lưu trữ, không thể cập nhật giá.");
        }
        UnitTypeResponse before = unitTypeResponse(type);
        boolean priceChanged = !java.util.Objects.equals(type.getMonthlyPrice(), request.monthlyPrice())
            || !java.util.Objects.equals(type.getSecurityDepositAmount(), request.securityDepositAmount());
        type.setMonthlyPrice(request.monthlyPrice());
        type.setSecurityDepositAmount(request.securityDepositAmount());
        if (type.getStatus() == UnitTypeStatus.draft) type.setStatus(UnitTypeStatus.awaiting_pricing);
        if (type.getStatus() == UnitTypeStatus.awaiting_pricing
            && type.getFacility().getStatus() == FacilityStatus.active
            && type.getMonthlyPrice() != null && type.getMonthlyPrice().signum() > 0
            && units.countByFacility_IdAndUnitType_IdAndStatus(
                type.getFacility().getId(), type.getId(), StorageUnitStatus.available) > 0) {
            type.setStatus(UnitTypeStatus.active);
            if (type.getPublishedRevision() == 0) type.setPublishedRevision(1);
        }
        UnitType saved = unitTypes.save(type);
        UnitTypeResponse after = unitTypeResponse(saved);
        audit.recordMutation("UNIT_TYPE_PRICED_BY_BUSINESS", "UnitType", saved.getId(), saved.getFacility().getId(), before, after);
        if (priceChanged && saved.getFacility().getStatus() == FacilityStatus.active) customerEmails.facilityChanged(saved.getFacility().getId(), "pricing:" + UUID.randomUUID(),
            "Thông báo cập nhật biểu giá", "Biểu giá mới đã được công bố trên web. Giá và nghĩa vụ của kỳ thuê đã chốt vẫn theo báo giá/hợp đồng đã chấp nhận; mức mới chỉ áp dụng cho giao dịch đủ điều kiện sau khi khách xác nhận.");
        return after;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public FacilityResponse publish(ActorPrincipal actor, UUID facilityId) {
        requireBusiness(actor);
        Facility facility = lockFacility(facilityId);
        if (facility.getStatus() == FacilityStatus.inactive || facility.getStatus() == FacilityStatus.maintenance) {
            throw ApiExceptions.conflict("Cơ sở đang tạm ngưng hoặc đã ngừng hoạt động. Vui lòng mở lại ở mục Trạng thái hoạt động trước khi công bố.");
        }
        validateAndActivate(facility);
        Facility saved = facilities.save(facility);
        FacilityResponse after = facilityResponse(saved);
        audit.recordMutation("FACILITY_PUBLISHED", "Facility", facilityId, facilityId, null, after);
        return after;
    }

    private void validateAndActivate(Facility facility) {
        UUID facilityId = facility.getId();
        List<UnitType> candidates = unitTypes.findByFacility_IdOrderByCodeAsc(facilityId).stream()
            .filter(type -> type.getStatus() == UnitTypeStatus.awaiting_pricing || type.getStatus() == UnitTypeStatus.active)
            .toList();
        if (candidates.isEmpty()) throw ApiExceptions.conflict("Chưa thể công bố: Manager cần khai báo ít nhất một loại kho thực tế.");
        for (UnitType type : candidates) {
            if (type.getMonthlyPrice() == null || type.getMonthlyPrice().signum() <= 0) {
                throw ApiExceptions.conflict("Chưa thể công bố: loại kho " + type.getCode() + " chưa được BO cấu hình giá thuê lớn hơn 0.");
            }
            if (units.countByFacility_IdAndUnitType_IdAndStatus(facilityId, type.getId(), StorageUnitStatus.available) <= 0) {
                throw ApiExceptions.conflict("Chưa thể công bố: loại kho " + type.getCode() + " chưa có gian kho sẵn sàng.");
            }
        }
        boolean hasEffectivePackage = packages
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(facilityId, LocalDate.now())
            .stream().anyMatch(p -> p.getEffectiveTo() == null || !p.getEffectiveTo().isBefore(LocalDate.now()));
        if (!hasEffectivePackage) {
            RentalPackagePolicyProvisioner.provisionRentalPackages(packages, facility);
            hasEffectivePackage = packages
                .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(facilityId, LocalDate.now())
                .stream().anyMatch(p -> p.getEffectiveTo() == null || !p.getEffectiveTo().isBefore(LocalDate.now()));
        }
        if (!hasEffectivePackage) throw ApiExceptions.conflict("Chưa thể công bố: cơ sở chưa được gán gói thuê đang áp dụng và còn hiệu lực. Vào Gói thuê để thêm cơ sở vào gói thuê.");

        candidates.forEach(type -> type.setStatus(UnitTypeStatus.active));
        candidates.forEach(type -> { if (type.getPublishedRevision() == 0) type.setPublishedRevision(1); });
        unitTypes.saveAll(candidates);
        facility.setStatus(FacilityStatus.active);
    }

    private void applyTechnical(UnitType type, ManagerUnitTypeRequest request) {
        type.setName(request.name().trim());
        type.setDescription(clean(request.description()));
        type.setLengthM(request.lengthM());
        type.setWidthM(request.widthM());
        type.setHeightM(request.heightM());
        type.setMaxLoadKg(request.maxLoadKg());
        if (request.maxStoredLoadKg() != null) type.setMaxStoredLoadKg(request.maxStoredLoadKg());
        if (request.storageAisleWidthM() != null) type.setStorageAisleWidthM(request.storageAisleWidthM());
        if (request.gateWidthM() != null) type.setGateWidthM(request.gateWidthM());
        if (request.gateHeightM() != null) type.setGateHeightM(request.gateHeightM());
        if (request.lowestObstructionHeightM() != null) type.setLowestObstructionHeightM(request.lowestObstructionHeightM());
        if (request.overheadClearanceM() != null) type.setOverheadClearanceM(request.overheadClearanceM());
        type.setRackCount(request.rackCount());
        type.setRackLengthM(request.rackLengthM());
        type.setRackWidthM(request.rackWidthM());
        type.setRackHeightM(request.rackHeightM());
        type.setAisleWidthM(request.aisleWidthM());
        type.setTrolleyType(request.trolleyType().trim());
        type.setSmallBoxCapacity(request.smallBoxCapacity());
        type.setLargeBoxCapacity(request.largeBoxCapacity());
        type.setAcceptedGoodsJson(approvedGoodsJson(request));
        type.setImageUrl(request.imageUrl() == null || request.imageUrl().isBlank() ? null : request.imageUrl().trim());
        type.setDetailImageUrl(clean(request.detailImageUrl()));
    }

    private void validateOperationalPairs(ManagerUnitTypeRequest request) {
        if ((request.gateWidthM() == null) != (request.gateHeightM() == null)) {
            throw ApiExceptions.validation("Kích thước cổng chưa đầy đủ.", List.of(
                "Chiều rộng và chiều cao thông thủy của cổng phải được nhập đồng thời."
            ));
        }
        if ((request.lowestObstructionHeightM() == null) != (request.overheadClearanceM() == null)) {
            throw ApiExceptions.validation("Thông số vật cản chưa đầy đủ.", List.of(
                "Cao độ vật cản/trần và khoảng hở an toàn phía trên phải được nhập đồng thời."
            ));
        }
        if (request.overheadClearanceM() != null && request.overheadClearanceM().signum() < 0) {
            throw ApiExceptions.validation("Khoảng hở an toàn phía trên kệ không hợp lệ.", List.of(
                "Khoảng hở phải lớn hơn hoặc bằng 0 m."
            ));
        }
        if (request.lowestObstructionHeightM() != null
            && request.lowestObstructionHeightM().compareTo(request.heightM()) > 0) {
            throw ApiExceptions.validation("Cao độ vật cản không hợp lệ.", List.of(
                "Chiều cao đến vật cản thấp nhất không được lớn hơn chiều cao gian kho."
            ));
        }
        if (request.gateWidthM() != null && request.gateWidthM().compareTo(request.widthM()) > 0) {
            throw ApiExceptions.validation("Chiều rộng cổng không hợp lệ.", List.of(
                "Chiều rộng thông thủy của cổng không được lớn hơn chiều rộng gian kho."
            ));
        }
        if (request.gateHeightM() != null && request.gateHeightM().compareTo(request.heightM()) > 0) {
            throw ApiExceptions.validation("Chiều cao cổng không hợp lệ.", List.of(
                "Chiều cao thông thủy của cổng không được lớn hơn chiều cao gian kho."
            ));
        }
        BigDecimal maximumFloorSpan = request.lengthM().max(request.widthM());
        if (request.aisleWidthM().compareTo(maximumFloorSpan) > 0) {
            throw ApiExceptions.validation("Chiều rộng lối đi chính không hợp lệ.", List.of(
                "Chiều rộng lối đi chính không thể lớn hơn kích thước gian kho."
            ));
        }
        if (request.storageAisleWidthM() != null
            && request.storageAisleWidthM().compareTo(maximumFloorSpan) > 0) {
            throw ApiExceptions.validation("Chiều rộng lối đi giữa các kệ không hợp lệ.", List.of(
                "Chiều rộng lối đi giữa các kệ không thể lớn hơn kích thước gian kho."
            ));
        }
        if (request.overheadClearanceM() != null) {
            BigDecimal limitingHeight = request.heightM().min(
                request.lowestObstructionHeightM() == null
                    ? request.heightM() : request.lowestObstructionHeightM());
            if (request.overheadClearanceM().compareTo(limitingHeight) >= 0) {
                throw ApiExceptions.validation("Khoảng hở an toàn không hợp lệ.", List.of(
                    "Khoảng hở an toàn phía trên kệ phải nhỏ hơn chiều cao sử dụng của gian kho."
                ));
            }
        }
        if (request.rackHeightM().compareTo(request.heightM()) > 0) {
            throw ApiExceptions.validation("Chiều cao khung không hợp lệ.", List.of(
                "Chiều cao khung không được lớn hơn chiều cao gian kho."
            ));
        }
        if (request.lowestObstructionHeightM() != null && request.overheadClearanceM() != null) {
            BigDecimal usableHeight = request.heightM().min(request.lowestObstructionHeightM())
                .subtract(request.overheadClearanceM());
            if (request.rackHeightM().compareTo(usableHeight) > 0) {
                throw ApiExceptions.validation("Chiều cao khung không phù hợp.", List.of(
                    "Chiều cao khung vượt quá chiều cao sử dụng của gian kho sau khi tính vật cản và khoảng hở an toàn."
                ));
            }
        }
    }

    private String extractZonePrefix(String zone) {
        if (zone == null || zone.isBlank()) return "A";
        String clean = java.text.Normalizer.normalize(zone.trim(), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace('đ', 'd')
            .replace('Đ', 'D');
        String stripped = clean.replaceAll("(?i)^(khu\\s*vuc|khu|zone)\\s*", "").trim();
        if (!stripped.isEmpty()) {
            String alphanumeric = stripped.replaceAll("[^a-zA-Z0-9]", "");
            if (!alphanumeric.isEmpty()) {
                return alphanumeric.toUpperCase();
            }
        }
        return clean.substring(0, Math.min(2, clean.length())).toUpperCase();
    }

    private String extractFloorNumber(String floor) {
        if (floor == null || floor.isBlank()) return "1";
        String digits = floor.replaceAll("[^0-9]", "").trim();
        if (!digits.isEmpty()) return digits;
        return floor.trim().toUpperCase();
    }

    private void createPhysicalUnits(UnitType type, int count, String floor, String zone) {
        String zonePrefix = extractZonePrefix(zone);
        String floorNum = extractFloorNumber(floor);
        int createdCount = 0;
        int index = 1;
        while (createdCount < count) {
            String candidateCode = "%s-%s%02d".formatted(zonePrefix, floorNum, index);
            if (!units.existsByCode(candidateCode)) {
                StorageUnit unit = new StorageUnit();
                unit.setFacility(type.getFacility());
                unit.setUnitType(type);
                unit.setCode(candidateCode);
                unit.setFloor(floor);
                unit.setZone(zone);
                unit.setStatus(StorageUnitStatus.available);
                unit.setRackLayoutJson(type.getRackLayoutJson());
                units.save(unit);
                createdCount++;
            }
            index++;
            if (index > 999) break;
        }
        if (createdCount != count) {
            throw ApiExceptions.conflict("Không đủ mã gian kho cho số lượng yêu cầu ở tầng và khu vực này. Vui lòng chọn vị trí khác hoặc giảm số lượng.");
        }
    }

    private String generateUnitTypeCode(UUID facilityId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = "UT-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
            if (unitTypes.findByFacility_IdAndCode(facilityId, code).isEmpty()) return code;
        }
        throw ApiExceptions.conflict("Không thể sinh mã loại kho duy nhất. Vui lòng thử lại.");
    }

    private com.storagehub.api.facility.FacilityLifecycleSummary lifecycleSummary(Facility facility) {
        UUID facilityId = facility.getId();
        long pendingHolds = reservations.countByFacility_IdAndStatusIn(facilityId, PENDING_HOLD_STATUSES);
        long processingReservations = reservations.countByFacility_IdAndStatusIn(facilityId, PROCESSING_RESERVATION_STATUSES);
        long activeContracts = contracts.countActiveByFacilityId(facilityId);
        long outstandingPayments = payments.countOutstandingByFacilityId(facilityId, OUTSTANDING_PAYMENT_STATUSES);
        long outstandingRefunds = refunds.countOutstandingByFacilityId(facilityId, OUTSTANDING_REFUND_STATUSES)
            + refundRequests.countOutstandingByFacilityId(facilityId, OUTSTANDING_REFUND_REQUEST_STATUSES);
        return new com.storagehub.api.facility.FacilityLifecycleSummary(
            facilityId, facility.getStatus(), pendingHolds, processingReservations,
            activeContracts, outstandingPayments, outstandingRefunds
        );
    }

    private void requireArchiveReady(Facility facility) {
        requireArchiveReady(lifecycleSummary(facility));
        if (units.countByFacility_IdAndStatusIn(facility.getId(), List.of(
            StorageUnitStatus.occupied, StorageUnitStatus.reserved,
            StorageUnitStatus.assigned, StorageUnitStatus.held)) > 0) {
            throw ApiExceptions.conflict("Không thể ngừng hoạt động cơ sở đang có gian kho đang thuê hoặc giữ chỗ.");
        }
    }

    private void requireArchiveReady(com.storagehub.api.facility.FacilityLifecycleSummary summary) {
        if (summary.canArchive()) return;
        throw ApiExceptions.conflict(
            "Chưa thể lưu trữ cơ sở. Còn " + summary.pendingHoldCount() + " đơn giữ kho chờ, "
                + summary.processingReservationCount() + " đơn đặt kho đang xử lý, "
                + summary.activeContractCount() + " hợp đồng đang hiệu lực, "
                + summary.outstandingPaymentCount() + " khoản thanh toán và "
                + summary.outstandingRefundCount() + " khoản hoàn tiền tồn đọng. "
                + "Chỉ có thể tạm ngưng nhận đơn mới."
        );
    }

    private void validateLifecycleTarget(FacilityStatus status) {
        if (status != FacilityStatus.maintenance && status != FacilityStatus.inactive) {
            throw ApiExceptions.validation("Yêu cầu chỉ được chọn Tạm ngưng nhận đơn mới hoặc Ngừng hoạt động.", null);
        }
    }

    private com.storagehub.api.facility.FacilityLifecycleChangeResponse lifecycleChangeResponse(
        FacilityLifecycleChangeRequest change
    ) {
        return new com.storagehub.api.facility.FacilityLifecycleChangeResponse(
            change.getId(), change.getFacilityId(), change.getFacilityName(), change.getRequestedBy(),
            change.getRequesterName(), change.getCreatedAt(), change.getRequestedStatus(), change.getReason(),
            change.getStatus(), change.getReviewedBy(), change.getReviewedAt(), change.getRejectionReason(),
            lifecycleSummary(facility(change.getFacilityId()))
        );
    }

    private void requireBusiness(ActorPrincipal actor) {
        if (actor == null || !actor.hasRole(RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Only Business Operations may perform this action");
        }
        authorization.require(actor, SystemPermission.MANAGE_POLICIES);
    }

    private void requireManager(ActorPrincipal actor, UUID facilityId) {
        if (actor == null || !actor.hasRole(RoleCode.MANAGER)) {
            throw ApiExceptions.forbidden("Only a Facility Manager may define unit types");
        }
        authorization.require(actor, SystemPermission.MANAGE_INVENTORY);
        scopes.assertCanManage(actor, facilityId);
    }

    private Facility facility(UUID id) {
        return facilities.findById(id).orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
    }

    private Facility lockFacility(UUID id) {
        return facilities.findByIdForUpdate(id).orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
    }

    private FacilityResponse facilityResponse(Facility f) {
        return new FacilityResponse(f.getId(), f.getCode(), f.getName(), f.getAddress(), f.getCity(),
            f.getPhone(), f.getAccessHours(), f.getImageUrl(), f.isClimateControlled(), f.getSecurityDescription(),
            managerNames(f.getId()),
            f.getStatus(), f.getCreatedAt(), f.getUpdatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> managerNames(UUID facilityId) {
        return userFacilityScopes.findByFacility_Id(facilityId).stream()
            .filter(scope -> scope.getUser().getStatus() == com.storagehub.domain.model.UserStatus.ACTIVE)
            .filter(scope -> scope.getUser().getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.MANAGER))
            .map(scope -> scope.getUser().getFullName()).sorted().toList();
    }

    private UnitTypeResponse unitTypeResponse(UnitType t) {
        long available = units.countByFacility_IdAndUnitType_IdAndStatus(t.getFacility().getId(), t.getId(), StorageUnitStatus.available);
        return new UnitTypeResponse(t.getId(), t.getFacility().getId(), t.getCode(), t.getName(), t.getDescription(), t.getLengthM(), t.getWidthM(), t.getHeightM(),
            t.getAreaM2(), t.getVolumeM3(), t.getMonthlyPrice(), t.getSecurityDepositAmount(), t.getImageUrl(), t.getMaxLoadKg(),
            t.getMaxStoredLoadKg(), t.getRackCount(), t.getRackLengthM(), t.getRackWidthM(), t.getRackHeightM(), t.getAisleWidthM(),
            t.getStorageAisleWidthM(), t.getGateWidthM(), t.getGateHeightM(), t.getLowestObstructionHeightM(), t.getOverheadClearanceM(),
            t.getTrolleyType(),
            t.getSmallBoxCapacity(), t.getLargeBoxCapacity(), t.getStatus(),
            units.countByFacility_IdAndUnitType_IdAndStatusIn(t.getFacility().getId(), t.getId(), StorageUnitStatus.operationalStatuses()), available,
            units.countByFacility_IdAndUnitType_IdAndStatus(t.getFacility().getId(), t.getId(), StorageUnitStatus.occupied), t.getCreatedAt(), t.getUpdatedAt(), t.getDetailImageUrl(),
            t.getPublishedRevision(), goodsScope(t).categoryIds(), goodsScope(t).materialIds());
    }
}
