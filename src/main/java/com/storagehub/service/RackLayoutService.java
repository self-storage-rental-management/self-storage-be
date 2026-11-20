package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.facility.RackLayout;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RackLayoutService {
    private static final int MAX_AUTOMATIC_RACK_COUNT = 50;
    private static final Set<String> BUILT_IN_DEMO_TYPE_CODES = Set.of("S", "M", "L", "XL");
    private static final RackLayout.Pallet CURRENT_DEMO_PALLET = new RackLayout.Pallet(
        210, 170, new BigDecimal("18"),
        StorageHubRules.calculatePalletWeightKg(BigDecimal.valueOf(210), BigDecimal.valueOf(170)),
        new BigDecimal("500"));
    private static final List<RackLayout.PalletRating> CURRENT_DEMO_PALLET_RATINGS = List.of(
        new RackLayout.PalletRating(210, 170, new BigDecimal("18"),
            StorageHubRules.calculatePalletWeightKg(BigDecimal.valueOf(210), BigDecimal.valueOf(170)), new BigDecimal("500")),
        new RackLayout.PalletRating(210, 80, new BigDecimal("18"),
            StorageHubRules.calculatePalletWeightKg(BigDecimal.valueOf(210), BigDecimal.valueOf(80)), new BigDecimal("500"))
    );

    private final StorageUnitRepository units;
    private final FacilityScopeService scopes;
    private final AdminAuthorizationService authorization;
    private final AuditLogService audit;
    private final ObjectMapper mapper;
    private final UnitTypeRepository unitTypes;
    private final ReservationCapacityService capacity;

    @Transactional(readOnly = true)
    public RackLayout.Saved readForManager(ActorPrincipal actor, UUID typeId) {
        UnitType type = unitType(typeId);
        requireManager(actor, type.getFacility().getId());
        // Recalculate the result from the saved configuration so layouts
        // created before the v6 result fields were added still expose the
        // current pallet, rack, compartment and cargo limits.
        return stored(type);
    }

    @Transactional(readOnly = true)
    public RackLayout.Preview previewDraft(ActorPrincipal actor, UUID facilityId, RackLayout.Draft draft) {
        requireManager(actor, facilityId);
        UnitType type = new UnitType();
        type.setLengthM(draft.lengthM());
        type.setWidthM(draft.widthM());
        type.setHeightM(draft.heightM());
        type.setMaxLoadKg(draft.maxLoadKg());
        type.setMaxStoredLoadKg(draft.maxStoredLoadKg());
        // Older clients may omit the optional operational widths. Use the
        // same defaults as automatic layout generation so the preview key can
        // be confirmed after the UnitType is created with those values.
        type.setAisleWidthM(draft.aisleWidthM() == null
            ? BigDecimal.valueOf(3) : draft.aisleWidthM());
        type.setStorageAisleWidthM(draft.storageAisleWidthM() == null
            ? BigDecimal.valueOf(2.5) : draft.storageAisleWidthM());
        type.setGateWidthM(draft.gateWidthM());
        type.setGateHeightM(draft.gateHeightM());
        type.setLowestObstructionHeightM(draft.lowestObstructionHeightM());
        type.setOverheadClearanceM(draft.overheadClearanceM());
        RackLayout.Configuration configuration = draft.configuration() != null
            ? automaticConfiguration(type, draft.configuration(), draft.rackCount())
            : draft.rackSpecification() != null
                ? autoConfiguration(type, draft.rackSpecification(), draft.rackCount())
                : autoConfiguration(type, draft.rackCount());
        return evaluate(actor, type, "create:" + facilityId, configuration);
    }

    @Transactional(readOnly = true)
    public RackLayout.Preview previewDraftForUnitType(ActorPrincipal actor, UUID typeId, RackLayout.Draft draft) {
        UnitType current = unitType(typeId);
        requireManager(actor, current.getFacility().getId());
        requireEditable(current);
        UnitType proposed = draftType(current, draft);
        RackLayout.Configuration configuration = draft.configuration() != null
            ? automaticConfiguration(proposed, draft.configuration(), draft.rackCount())
            : draft.rackSpecification() != null
                ? autoConfiguration(proposed, draft.rackSpecification(), draft.rackCount())
                : autoConfiguration(proposed, draft.rackCount());
        return evaluate(actor, proposed, editState(current), configuration);
    }

    void confirmNewType(ActorPrincipal actor, UnitType type, RackLayout.Save request) {
        if (request == null) {
            if (type.getRackCount() <= 0) {
                throw ApiExceptions.validation("Vui lòng xem trước và xác nhận cấu hình kệ pallet.", null);
            }
            // The create flow may intentionally provide only rackCount. The
            // backend still builds and validates the complete layout before
            // persisting the new unit type.
            RackLayout.Configuration configuration = autoConfiguration(type, type.getRackCount());
            storeCalculated(type, RackLayoutCalculator.calculate(type, configuration));
            return;
        }
        storeConfirmed(type, evaluate(actor, type, "create:" + type.getFacility().getId(),
            automaticConfiguration(type, request.configuration(), request.configuration().requestedRackCount())), request);
    }

    @Transactional(readOnly = true)
    public RackLayout.Preview preview(ActorPrincipal actor, UUID typeId, RackLayout.Configuration configuration) {
        UnitType type = unitType(typeId);
        requireManager(actor, type.getFacility().getId());
        requireEditable(type);
        return evaluate(actor, type, editState(type), automaticConfiguration(type, configuration,
            configuration == null ? null : configuration.requestedRackCount()));
    }

    @Transactional
    public RackLayout.Saved save(ActorPrincipal actor, UUID typeId, RackLayout.Save request) {
        UnitType type = unitTypes.findByIdForUpdate(typeId)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy loại gian kho."));
        requireManager(actor, type.getFacility().getId());
        requireEditable(type);
        RackLayout.Saved before = stored(type.getRackLayoutJson());
        RackLayout.Saved saved = storeConfirmed(type, evaluate(actor, type, editState(type),
            automaticConfiguration(type, request.configuration(), request.configuration().requestedRackCount())), request);
        type.setPublishedRevision(Math.max(1, type.getPublishedRevision() + 1));
        audit.recordMutation("UNIT_TYPE_RACK_LAYOUT_SAVED", "UnitType", typeId,
            type.getFacility().getId(), before, saved);
        return saved;
    }

    private RackLayout.Saved storeConfirmed(UnitType type, RackLayout.Preview preview, RackLayout.Save request) {
        if (!preview.previewKey().equals(request.previewKey())) {
            throw ApiExceptions.conflict("Cấu hình hoặc thông số loại gian đã thay đổi. Vui lòng kiểm tra lại trước khi lưu.");
        }
        if (!preview.result().palletRatingConfigured()) {
            throw ApiExceptions.conflict("Cấu hình tấm kê tự động chưa hợp lệ.");
        }
        return storeCalculated(type, preview.result());
    }

    public void initializeDemoLayout(UnitType type, RackLayout.Configuration configuration) {
        if (type.getRackLayoutJson() == null) storeCalculated(type, RackLayoutCalculator.calculate(type, configuration));
    }

    @Transactional
    public void upgradeDemoLayout(UUID typeId, long expectedVersion, RackLayout.Configuration previous,
                                  RackLayout.Configuration replacement) {
        UnitType type = unitTypes.findByIdForUpdate(typeId).orElse(null);
        if (type == null || type.getVersion() != expectedVersion) return;
        RackLayout.Saved saved = stored(type.getRackLayoutJson());
        if (saved == null || !previous.equals(saved.result().configuration()) || hasUnitsInUse(type)) return;
        storeCalculated(type, RackLayoutCalculator.calculate(type, replacement));
        type.setPublishedRevision(Math.max(1, type.getPublishedRevision() + 1));
    }

    @Transactional
    public void upgradeLegacyDemoLayout(UUID typeId, long expectedVersion, RackLayout.Configuration replacement) {
        UnitType type = unitTypes.findByIdForUpdate(typeId).orElse(null);
        if (type == null || type.getVersion() != expectedVersion) return;
        RackLayout.Saved saved = stored(type.getRackLayoutJson());
        if (saved == null || !isLegacyPalletLayout(saved.result().configuration()) || hasUnitsInUse(type)) return;
        storeCalculated(type, RackLayoutCalculator.calculate(type, replacement));
        type.setPublishedRevision(Math.max(1, type.getPublishedRevision() + 1));
    }

    private RackLayout.Saved storeCalculated(UnitType type, RackLayout.Result result) {
        RackLayout.Saved saved = new RackLayout.Saved(result, Instant.now());
        String snapshot = json(saved);
        type.setRackLayoutJson(snapshot);
        // v8.1 derives pallet mass from generated dimensions and keeps the
        // 500 kg payload convention. Keep the catalog column readable for historical snapshots, but do not write
        // a new rating catalog that could be mistaken for calculation input.
        if (!StorageHubRules.RULES_VERSION.equals(result.rulesVersion())
            && result.configuration().palletRatings() != null
            && !result.configuration().palletRatings().isEmpty()) {
            type.setPalletRatingCatalogJson(json(result.configuration().palletRatings()));
        }
        applyRackSummary(type, result);
        // A type is a template; physical units own their snapshots. Update
        // only units that are not in use (or have never received a snapshot),
        // so a later template edit cannot rewrite an active unit's history.
        if (type.getId() != null) {
            var physicalUnits = units.findByFacility_Id(type.getFacility().getId()).stream()
                .filter(unit -> unit.getUnitType() != null && unit.getUnitType().getId().equals(type.getId()))
                .filter(unit -> unit.getRackLayoutJson() == null
                    || unit.getStatus() == StorageUnitStatus.available
                    || unit.getStatus() == StorageUnitStatus.maintenance)
                .toList();
            physicalUnits.forEach(unit -> unit.setRackLayoutJson(snapshot));
            if (!physicalUnits.isEmpty()) units.saveAll(physicalUnits);
        }
        return saved;
    }

    private RackLayout.Configuration withRatingCatalog(UnitType type, RackLayout.Configuration configuration) {
        if (configuration == null
            || (configuration.palletRatings() != null && !configuration.palletRatings().isEmpty())
            || type == null
            || type.getPalletRatingCatalogJson() == null
            || type.getPalletRatingCatalogJson().isBlank()) return configuration;
        try {
            List<RackLayout.PalletRating> ratings = mapper.readValue(type.getPalletRatingCatalogJson(),
                mapper.getTypeFactory().constructCollectionType(List.class, RackLayout.PalletRating.class));
            return new RackLayout.Configuration(configuration.clearances(), configuration.racks(), configuration.pallet(),
                configuration.autoGeneratePallet(), ratings, configuration.requestedRackCount(), configuration.layoutMode());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read pallet rating catalog", exception);
        }
    }

    void applyRackSummary(UnitType type, RackLayout.Result result) {
        if (result == null) return;
        RackLayout.Rack first = result.configuration().racks().getFirst();
        type.setRackCount(result.racks().size());
        type.setRackLengthM(rackDimensionMeters(first.lengthCm()));
        type.setRackWidthM(rackDimensionMeters(first.widthCm()));
        type.setRackHeightM(rackDimensionMeters(first.heightCm()));
        type.setSmallBoxCapacity(result.smallBoxesPerUnit());
        type.setLargeBoxCapacity(result.largeBoxesPerUnit());
    }

    /** Revalidates and applies a layout carried by an approved unit-type review. */
    void applyReviewedLayout(UnitType type, RackLayout.Save request) {
        if (request == null) return;
        RackLayout.Configuration configuration = automaticConfiguration(type, request.configuration(),
            request.configuration() == null ? null : request.configuration().requestedRackCount());
        RackLayout.Result result = RackLayoutCalculator.calculate(type, configuration);
        if (!result.palletRatingConfigured()) {
            throw ApiExceptions.conflict("Cấu hình tấm kê tự động chưa hợp lệ.");
        }
        storeCalculated(type, result);
    }

    private BigDecimal rackDimensionMeters(BigDecimal cm) {
        return cm.movePointLeft(2).setScale(2, RoundingMode.HALF_UP).max(new BigDecimal("0.01"));
    }

    private UnitType draftType(UnitType current, RackLayout.Draft draft) {
        UnitType proposed = new UnitType();
        proposed.setLengthM(draft.lengthM());
        proposed.setWidthM(draft.widthM());
        proposed.setHeightM(draft.heightM());
        proposed.setMaxLoadKg(draft.maxLoadKg());
        proposed.setMaxStoredLoadKg(draft.maxStoredLoadKg() == null
            ? current.getMaxStoredLoadKg() : draft.maxStoredLoadKg());
        proposed.setAisleWidthM(draft.aisleWidthM() == null
            ? current.getAisleWidthM() : draft.aisleWidthM());
        proposed.setStorageAisleWidthM(draft.storageAisleWidthM() == null
            ? current.getStorageAisleWidthM() : draft.storageAisleWidthM());
        proposed.setGateWidthM(draft.gateWidthM());
        proposed.setGateHeightM(draft.gateHeightM());
        proposed.setLowestObstructionHeightM(draft.lowestObstructionHeightM());
        proposed.setOverheadClearanceM(draft.overheadClearanceM());
        proposed.setRackCount(draft.rackCount() == null ? current.getRackCount() : draft.rackCount());
        proposed.setRackLengthM(current.getRackLengthM());
        proposed.setRackWidthM(current.getRackWidthM());
        proposed.setRackHeightM(current.getRackHeightM());
        proposed.setTrolleyType(current.getTrolleyType());
        return proposed;
    }

    @Transactional(readOnly = true)
    public Page<RackLayout.TypeSummary> listForBusiness(ActorPrincipal actor, Pageable pageable) {
        requireBusiness(actor);
        boolean global = scopes.hasGlobalScope(actor);
        List<UUID> facilities = actor.facilityScopes().keySet().stream()
            .filter(id -> scopes.canAccessFacility(actor, id, FacilityScopeLevel.READ))
            .toList();
        if (!global && facilities.isEmpty()) return Page.empty(pageable);
        return unitTypes.findRackLayoutTypes(global, facilities, pageable)
            .map(row -> new RackLayout.TypeSummary(row.getId(), row.getCode(), row.getName(), row.getFacilityName()));
    }

    @Transactional(readOnly = true)
    public RackLayout.Saved readForBusiness(ActorPrincipal actor, UUID typeId) {
        requireBusiness(actor);
        UnitType type = unitType(typeId);
        scopes.assertCanRead(actor, type.getFacility().getId());
        RackLayout.Saved result = stored(type);
        if (result == null) throw ApiExceptions.notFound("Loại gian kho chưa có cấu hình kệ pallet.");
        return result;
    }

    @Transactional(readOnly = true)
    public RackLayout.Result readForCustomer(ActorPrincipal actor, UUID facilityId, UUID unitTypeId) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) throw ApiExceptions.forbidden("Chỉ khách hàng được xem danh mục đặt kho tại đây.");
        UnitType type = unitTypes.findById(unitTypeId)
            .filter(candidate -> candidate.getStatus() == UnitTypeStatus.active)
            .filter(candidate -> candidate.getFacility().getId().equals(facilityId)
                && candidate.getFacility().getStatus() == FacilityStatus.active)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy loại kho đang mở cho thuê."));
        RackLayout.Result result = layoutForPacking(type, type.getRackLayoutJson());
        if (result == null) return null;
        return result.withAvailabilityStatus(availabilityStatus(type)).withSamplePlans(
            PalletPacking.planSample(result, result.smallBox(), result.smallBoxesPerUnit()),
            PalletPacking.planSample(result, result.largeBox(), result.largeBoxesPerUnit()),
            null);
    }

    private String availabilityStatus(UnitType type) {
        long total = units.countByFacility_IdAndUnitType_Id(type.getFacility().getId(), type.getId());
        if (total <= 0) return "NOT_EVALUATED";
        long available = units.countByFacility_IdAndUnitType_IdAndStatus(
            type.getFacility().getId(), type.getId(), StorageUnitStatus.available);
        return available > 0 ? "AVAILABLE" : "FULL";
    }

    RackLayout.Result layoutForPacking(UnitType type, String json) {
        RackLayout.Saved saved = stored(json);
        if (saved == null) return null;
        RackLayout.Configuration configuration = withRatingCatalog(type,
            normalizeLegacyDemoConfiguration(type, saved.result().rulesVersion(), saved.result().configuration()));
        return RackLayoutCalculator.calculate(type, configuration);
    }

    private RackLayout.Preview evaluate(ActorPrincipal actor, UnitType type, String scope, RackLayout.Configuration configuration) {
        RackLayout.Result result = RackLayoutCalculator.calculate(type, configuration);
        String state = actor.userId() + ":" + scope + ":" + type.getLengthM().stripTrailingZeros()
            + ":" + type.getWidthM().stripTrailingZeros() + ":" + type.getHeightM().stripTrailingZeros()
            + ":" + type.getMaxLoadKg().stripTrailingZeros()
            + ":" + (type.getMaxStoredLoadKg() == null ? "" : type.getMaxStoredLoadKg().stripTrailingZeros())
            + ":" + valueOr(type.getStorageAisleWidthM(), BigDecimal.valueOf(2.5))
            + ":" + valueOr(type.getAisleWidthM(), BigDecimal.valueOf(3))
            + ":" + value(type.getGateWidthM())
            + ":" + value(type.getGateHeightM())
            + ":" + value(type.getLowestObstructionHeightM())
            + ":" + value(type.getOverheadClearanceM())
            + ":" + json(result.configuration());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            return new RackLayout.Preview(HexFormat.of().formatHex(digest), result);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String editState(UnitType type) {
        return "edit:" + type.getId() + ":" + type.getVersion();
    }

    private RackLayout.Saved stored(UnitType type) {
        RackLayout.Saved snapshot = stored(type.getRackLayoutJson());
        if (snapshot == null) return null;
        RackLayout.Configuration configuration = withRatingCatalog(type,
            normalizeLegacyDemoConfiguration(type, snapshot.result().rulesVersion(), snapshot.result().configuration()));
        return new RackLayout.Saved(RackLayoutCalculator.calculate(type, configuration), snapshot.savedAt());
    }

    private RackLayout.Saved stored(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, RackLayout.Saved.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read saved rack layout", exception);
        }
    }

    /**
     * Builds the standard two-row layout used when a Manager supplies only
     * the number of racks. Coordinates and orientation are deliberately
     * backend-owned; clients can still submit the older explicit layout for
     * exceptional floor plans.
     */
    RackLayout.Configuration autoConfiguration(UnitType type, Integer requestedRackCount) {
        int rackCount = requestedRackCount != null && requestedRackCount > 0
            ? requestedRackCount : type.getRackCount();
        if (rackCount <= 0) {
            throw ApiExceptions.validation("Chưa thể tự sinh bố trí khung kệ.", List.of(
                "Số khung mỗi gian phải lớn hơn 0."
            ));
        }
        BigDecimal rackLengthCm = positiveOr(type.getRackLengthM(), BigDecimal.valueOf(4)).movePointRight(2);
        BigDecimal rackWidthCm = positiveOr(type.getRackWidthM(), BigDecimal.valueOf(2)).movePointRight(2);
        BigDecimal rackHeightCm = positiveOr(type.getRackHeightM(), BigDecimal.valueOf(4.5)).movePointRight(2);
        RackLayout.Clearances clearances = fixedClearances();
        BigDecimal floorLengthCm = positiveOr(type.getLengthM(), BigDecimal.ONE).movePointRight(2);
        BigDecimal floorWidthCm = positiveOr(type.getWidthM(), BigDecimal.ONE).movePointRight(2);
        RackLayout.Pallet fixedPallet = new RackLayout.Pallet(210, 170,
            StorageHubRules.PALLET_HEIGHT_CM,
            StorageHubRules.calculatePalletWeightKg(BigDecimal.valueOf(210), BigDecimal.valueOf(170)),
            StorageHubRules.PALLET_MAX_PAYLOAD_KG);
        int levelCount = Math.max(1, RackLayoutCalculator.maxLevelCount(
            defaultTemplate(rackLengthCm, rackWidthCm, rackHeightCm, fixedPallet, clearances),
            fixedPallet, clearances));
        RackLayout.Rack template = defaultTemplate(rackLengthCm, rackWidthCm, rackHeightCm, fixedPallet, clearances);
        template = new RackLayout.Rack(template.code(), template.lengthCm(), template.widthCm(), template.heightCm(),
            template.xCm(), template.yCm(), template.orientationDeg(), template.uprightFrameCount(),
            template.uprightSizeCm(), BigDecimal.valueOf(levelCount), template.beamHeightCm(),
            template.firstBeamBottomCm(), template.palletsPerCompartment(), template.maxLoadedPalletKg(),
            template.maxRackLoadKg(), template.beamOuterSpanCm(), template.maxBeamPairLoadKg());
        return buildAutomaticConfiguration(type, template, rackCount);
    }

    RackLayout.Configuration autoConfiguration(UnitType type, RackLayout.RackSpecification specification,
                                               Integer requestedRackCount) {
        int rackCount = requestedRackCount != null && requestedRackCount > 0
            ? requestedRackCount : type.getRackCount();
        if (rackCount <= 0) {
            throw ApiExceptions.validation("Chưa thể tự sinh bố trí khung kệ.", List.of(
                "Số khung mỗi gian phải lớn hơn 0."
            ));
        }
        RackLayout.Rack template = new RackLayout.Rack("TEMPLATE",
            specification.lengthCm(), specification.widthCm(), specification.heightCm(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.valueOf(specification.uprightFrameCount()),
            StorageHubRules.UPRIGHT_SIZE_CM,
            BigDecimal.valueOf(specification.levelCount()),
            StorageHubRules.BEAM_HEIGHT_CM,
            StorageHubRules.FIRST_BEAM_BOTTOM_CM,
            BigDecimal.valueOf(specification.palletsPerCompartment()),
            null, specification.maxRackLoadKg(), specification.widthCm(),
            specification.maxBeamPairLoadKg());
        return buildAutomaticConfiguration(type, template, rackCount);
    }

    private RackLayout.Configuration automaticConfiguration(UnitType type, RackLayout.Configuration source,
                                                            Integer requestedRackCount) {
        if (source == null || !Boolean.TRUE.equals(source.autoGeneratePallet())) return source;
        List<RackLayout.Rack> sourceRacks = source.racks() == null ? List.of() : source.racks();
        boolean generatedRows = "AUTO_BALANCED_TWO_ROWS".equalsIgnoreCase(source.layoutMode())
            || (source.requestedRackCount() != null && source.requestedRackCount() != sourceRacks.size());
        if (!generatedRows) return source;
        int rackCount = requestedRackCount != null && requestedRackCount > 0
            ? requestedRackCount
            : source.requestedRackCount() != null && source.requestedRackCount() > 0
                ? source.requestedRackCount() : sourceRacks.size();
        if (sourceRacks.isEmpty()) return autoConfiguration(type, rackCount);
        return buildAutomaticConfiguration(type, sourceRacks.getFirst(), rackCount);
    }

    private RackLayout.Configuration buildAutomaticConfiguration(UnitType type, RackLayout.Rack template,
                                                                  int rackCount) {
        if (rackCount <= 0) {
            throw ApiExceptions.validation("Chưa thể tự sinh bố trí khung kệ.", List.of(
                "Số khung mỗi gian phải lớn hơn 0."
            ));
        }
        BigDecimal rackLengthCm = positiveOr(template.lengthCm(), BigDecimal.valueOf(4).movePointRight(2));
        BigDecimal rackWidthCm = positiveOr(template.widthCm(), BigDecimal.valueOf(2).movePointRight(2));
        BigDecimal rackHeightCm = positiveOr(template.heightCm(), BigDecimal.valueOf(4.5).movePointRight(2));
        BigDecimal floorLengthCm = positiveOr(type.getLengthM(), BigDecimal.ONE).movePointRight(2);
        BigDecimal floorWidthCm = positiveOr(type.getWidthM(), BigDecimal.ONE).movePointRight(2);
        BigDecimal storageAisleCm = positiveOr(type.getStorageAisleWidthM(), BigDecimal.valueOf(2.5)).movePointRight(2);
        BigDecimal mainAisleCm = positiveOr(type.getAisleWidthM(), BigDecimal.valueOf(3)).movePointRight(2);
        RackLayout.Clearances clearances = fixedClearances();
        BigDecimal wallCm = StorageHubRules.WALL_CLEARANCE_CM;

        int firstRowCount = (rackCount + 1) / 2;
        int secondRowCount = rackCount / 2;
        List<RackLayout.Rack> racks = autoRows(rackCount, firstRowCount, secondRowCount,
            rackLengthCm, rackWidthCm, rackHeightCm, template, storageAisleCm, mainAisleCm,
            wallCm, floorLengthCm, floorWidthCm, true);
        if (racks == null) {
            racks = autoRows(rackCount, firstRowCount, secondRowCount,
                rackLengthCm, rackWidthCm, rackHeightCm, template, storageAisleCm, mainAisleCm,
                wallCm, floorLengthCm, floorWidthCm, false);
        }
        if (racks == null) {
            throw ApiExceptions.validation("Không thể tự sinh bố trí khung kệ.",
                automaticLayoutErrors(type, rackCount, rackLengthCm, rackWidthCm, storageAisleCm,
                    mainAisleCm, wallCm));
        }
        return new RackLayout.Configuration(clearances, racks, null, true, List.of(),
            rackCount, "AUTO_BALANCED_TWO_ROWS");
    }

    private List<String> automaticLayoutErrors(UnitType type, int requestedRackCount,
                                                BigDecimal rackLengthCm, BigDecimal rackWidthCm,
                                                BigDecimal storageAisleCm, BigDecimal mainAisleCm,
                                                BigDecimal wallCm) {
        BigDecimal floorLengthCm = positiveOr(type.getLengthM(), BigDecimal.ONE).movePointRight(2);
        BigDecimal floorWidthCm = positiveOr(type.getWidthM(), BigDecimal.ONE).movePointRight(2);
        BigDecimal usableLengthCm = floorLengthCm.subtract(wallCm.multiply(BigDecimal.valueOf(2)))
            .subtract(StorageHubRules.MAX_PALLET_OVERHANG_CM.multiply(BigDecimal.valueOf(2)));
        BigDecimal usableWidthCm = floorWidthCm.subtract(wallCm.multiply(BigDecimal.valueOf(2)))
            .subtract(StorageHubRules.MAX_PALLET_OVERHANG_CM.multiply(BigDecimal.valueOf(2)));
        int maximumRackCount = 0;
        for (int count = 1; count <= MAX_AUTOMATIC_RACK_COUNT; count++) {
            if (automaticLayoutFits(count, rackLengthCm, rackWidthCm, storageAisleCm, mainAisleCm,
                usableLengthCm, usableWidthCm)) maximumRackCount = count;
        }

        List<String> details = new ArrayList<>();
        if (maximumRackCount < requestedRackCount) {
            details.add("Với kích thước khung và lối đi hiện tại, gian kho chỉ bố trí được tối đa "
                + maximumRackCount + " khung; đang yêu cầu " + requestedRackCount + " khung.");
        }
        details.add(automaticDimensionGuidance(requestedRackCount, storageAisleCm, mainAisleCm,
            usableLengthCm, usableWidthCm));
        return List.copyOf(details);
    }

    private boolean automaticLayoutFits(int rackCount, BigDecimal rackLengthCm, BigDecimal rackWidthCm,
                                        BigDecimal storageAisleCm, BigDecimal mainAisleCm,
                                        BigDecimal usableLengthCm, BigDecimal usableWidthCm) {
        int firstRowCount = (rackCount + 1) / 2;
        int secondRowCount = rackCount / 2;
        return automaticOrientationFits(firstRowCount, secondRowCount, rackWidthCm, rackLengthCm,
            storageAisleCm, mainAisleCm, usableLengthCm, usableWidthCm)
            || automaticOrientationFits(firstRowCount, secondRowCount, rackLengthCm, rackWidthCm,
                storageAisleCm, mainAisleCm, usableLengthCm, usableWidthCm);
    }

    private boolean automaticOrientationFits(int firstRowCount, int secondRowCount,
                                              BigDecimal alongCm, BigDecimal acrossCm,
                                              BigDecimal storageAisleCm, BigDecimal mainAisleCm,
                                              BigDecimal usableLengthCm, BigDecimal usableWidthCm) {
        BigDecimal requiredLength = alongCm.multiply(BigDecimal.valueOf(firstRowCount))
            .add(storageAisleCm.multiply(BigDecimal.valueOf(Math.max(0, firstRowCount - 1))));
        BigDecimal rowCount = BigDecimal.valueOf(secondRowCount > 0 ? 2 : 1);
        BigDecimal requiredWidth = acrossCm.multiply(rowCount)
            .add(secondRowCount > 0 ? mainAisleCm : BigDecimal.ZERO);
        return requiredLength.compareTo(usableLengthCm) <= 0
            && requiredWidth.compareTo(usableWidthCm) <= 0;
    }

    private String automaticDimensionGuidance(int rackCount, BigDecimal storageAisleCm, BigDecimal mainAisleCm,
                                              BigDecimal usableLengthCm, BigDecimal usableWidthCm) {
        int firstRowCount = Math.max(1, (rackCount + 1) / 2);
        int secondRowCount = rackCount / 2;
        BigDecimal alongLimit = usableLengthCm.subtract(
            storageAisleCm.multiply(BigDecimal.valueOf(Math.max(0, firstRowCount - 1))))
            .divide(BigDecimal.valueOf(firstRowCount), 6, RoundingMode.DOWN).max(BigDecimal.ZERO);
        BigDecimal acrossLimit = usableWidthCm.subtract(secondRowCount > 0 ? mainAisleCm : BigDecimal.ZERO)
            .divide(BigDecimal.valueOf(secondRowCount > 0 ? 2 : 1), 6, RoundingMode.DOWN).max(BigDecimal.ZERO);
        BigDecimal maximumDimension = alongLimit.max(acrossLimit);
        BigDecimal minimumDimension = alongLimit.min(acrossLimit);
        return cmLimit(maximumDimension) + " × " + cmLimit(minimumDimension) + " cm";
    }

    private String cmLimit(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).toPlainString();
    }

    private RackLayout.Clearances fixedClearances() {
        return new RackLayout.Clearances(
            StorageHubRules.PALLET_GAP_CM, StorageHubRules.PALLET_END_CLEARANCE_CM, BigDecimal.ZERO,
            StorageHubRules.HANDLING_CLEARANCE_CM, StorageHubRules.MAX_PALLET_OVERHANG_CM,
            StorageHubRules.WALL_CLEARANCE_CM);
    }

    private RackLayout.Rack defaultTemplate(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                                            RackLayout.Pallet pallet, RackLayout.Clearances clearances) {
        return new RackLayout.Rack("TEMPLATE", lengthCm, widthCm, heightCm,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(3),
            StorageHubRules.UPRIGHT_SIZE_CM, BigDecimal.ONE, StorageHubRules.BEAM_HEIGHT_CM,
            StorageHubRules.FIRST_BEAM_BOTTOM_CM, BigDecimal.ONE, null,
            BigDecimal.valueOf(1200), widthCm, BigDecimal.valueOf(100));
    }

    private List<RackLayout.Rack> autoRows(int total, int firstRowCount, int secondRowCount,
                                           BigDecimal rackLengthCm, BigDecimal rackWidthCm,
                                           BigDecimal rackHeightCm, RackLayout.Rack template,
                                           BigDecimal storageAisleCm, BigDecimal mainAisleCm,
                                           BigDecimal wallCm, BigDecimal floorLengthCm,
                                           BigDecimal floorWidthCm, boolean rotated) {
        BigDecimal along = rotated ? rackWidthCm : rackLengthCm;
        BigDecimal across = rotated ? rackLengthCm : rackWidthCm;
        BigDecimal maxFirstX = wallCm.add(along.multiply(BigDecimal.valueOf(firstRowCount)))
            .add(storageAisleCm.multiply(BigDecimal.valueOf(Math.max(0, firstRowCount - 1))));
        BigDecimal maxSecondX = wallCm.add(along.multiply(BigDecimal.valueOf(secondRowCount)))
            .add(storageAisleCm.multiply(BigDecimal.valueOf(Math.max(0, secondRowCount - 1))));
        BigDecimal secondRowEndY = secondRowCount > 0
            ? wallCm.add(across.multiply(BigDecimal.valueOf(2))).add(mainAisleCm)
            : wallCm.add(across);
        if (maxFirstX.compareTo(floorLengthCm.subtract(wallCm)) > 0
            || maxSecondX.compareTo(floorLengthCm.subtract(wallCm)) > 0
            || secondRowEndY.compareTo(floorWidthCm.subtract(wallCm)) > 0) return null;
        return autoRowsUnchecked(total, firstRowCount, secondRowCount, rackLengthCm, rackWidthCm,
            rackHeightCm, template, storageAisleCm, mainAisleCm, wallCm, rotated);
    }

    private List<RackLayout.Rack> autoRowsUnchecked(int total, int firstRowCount, int secondRowCount,
                                                    BigDecimal rackLengthCm, BigDecimal rackWidthCm,
                                                    BigDecimal rackHeightCm, RackLayout.Rack template,
                                                    BigDecimal storageAisleCm, BigDecimal mainAisleCm,
                                                    BigDecimal wallCm, boolean rotated) {
        List<RackLayout.Rack> racks = new ArrayList<>(total);
        BigDecimal along = rotated ? rackWidthCm : rackLengthCm;
        BigDecimal across = rotated ? rackLengthCm : rackWidthCm;
        for (int index = 0; index < total; index++) {
            boolean secondRow = index >= firstRowCount;
            int indexInRow = secondRow ? index - firstRowCount : index;
            BigDecimal x = wallCm.add(along.add(storageAisleCm).multiply(BigDecimal.valueOf(indexInRow)));
            BigDecimal y = wallCm.add(secondRow ? across.add(mainAisleCm) : BigDecimal.ZERO);
            racks.add(new RackLayout.Rack("R%02d".formatted(index + 1), rackLengthCm, rackWidthCm,
                rackHeightCm, x, y, rotated ? BigDecimal.valueOf(90) : BigDecimal.ZERO,
                template.uprightFrameCount(), template.uprightSizeCm(), template.levelCount(),
                template.beamHeightCm(), template.firstBeamBottomCm(),
                // This is a legacy output field. In automatic mode it is
                // filled while calculating the preview and must not become
                // part of the next confirmation key.
                template.palletsPerCompartment(), null, template.maxRackLoadKg(),
                template.beamOuterSpanCm(), template.maxBeamPairLoadKg()));
        }
        return List.copyOf(racks);
    }

    private BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize rack layout", exception);
        }
    }

    private String value(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String valueOr(BigDecimal value, BigDecimal fallback) {
        return value(value == null ? fallback : value);
    }

    private boolean isLegacyPalletLayout(RackLayout.Configuration configuration) {
        RackLayout.Pallet pallet = configuration.pallet();
        return pallet != null && (pallet.lengthCm() == 120 || pallet.lengthCm() == 170
            || pallet.widthCm() == 80 || pallet.widthCm() == 164
            || (pallet.heightCm() != null && pallet.heightCm().compareTo(new BigDecimal("14.4")) == 0));
    }

    /**
     * Old demo records can remain in the database when the bootstrap runner is
     * disabled, a different profile is used, or the record does not exactly
     * match one of the historical JSON snapshots. Keep one calculation path
     * for the built-in S/M/L/XL demo types so Customer, BO and packing do not
     * expose the old two-pallet 120 x 80 configuration.
     */
    private RackLayout.Configuration normalizeLegacyDemoConfiguration(UnitType type, String rulesVersion,
                                                                        RackLayout.Configuration configuration) {
        boolean currentAutoLayoutMatchesAisles = StorageHubRules.RULES_VERSION.equals(rulesVersion)
            && Boolean.TRUE.equals(configuration.autoGeneratePallet())
            && !hasDemoAisleMismatch(type, configuration);
        if (!isBuiltInDemoType(type)
            || currentAutoLayoutMatchesAisles) {
            return configuration;
        }

        List<RackLayout.Rack> sourceRacks = configuration.racks();
        int racksPerRow = Math.max(1, (sourceRacks.size() + 1) / 2);
        List<RackLayout.Rack> racks = new ArrayList<>(sourceRacks.size());
        // Rebuild legacy demo coordinates from the current unit-type inputs.
        // The old normalizer used a fixed 250 cm rack gap, which contradicted
        // the stored XL design value of 310 cm and produced repeated Rxx
        // clearance errors even though the warehouse data was correct.
        BigDecimal frameGap = positiveOr(type.getStorageAisleWidthM(), new BigDecimal("2.5"))
            .movePointRight(2);
        BigDecimal firstX = new BigDecimal("275");
        BigDecimal mainAisle = positiveOr(type.getAisleWidthM(), new BigDecimal("3"))
            .movePointRight(2);
        for (int index = 0; index < sourceRacks.size(); index++) {
            RackLayout.Rack source = sourceRacks.get(index);
            BigDecimal x = firstX.add(frameGap.add(new BigDecimal("200"))
                .multiply(BigDecimal.valueOf(index % racksPerRow)));
            BigDecimal y;
            if (index < racksPerRow) {
                y = BigDecimal.valueOf(30);
            } else {
                y = BigDecimal.valueOf(30).add(new BigDecimal("400")).add(mainAisle);
            }
            BigDecimal legacyBeamLoad = source.maxBeamPairLoadKg() != null
                ? source.maxBeamPairLoadKg()
                : source.maxLoadedPalletKg() != null ? source.maxLoadedPalletKg() : BigDecimal.valueOf(100);
            racks.add(new RackLayout.Rack(
                source.code(), source.lengthCm(), source.widthCm(), source.heightCm(), x, y,
                BigDecimal.valueOf(90), source.uprightFrameCount(), source.uprightSizeCm(),
                source.levelCount(), source.beamHeightCm(), source.firstBeamBottomCm(),
                BigDecimal.ONE, source.maxLoadedPalletKg(), source.maxRackLoadKg(),
                source.beamOuterSpanCm(), legacyBeamLoad));
        }

        RackLayout.Clearances clearances = new RackLayout.Clearances(
            BigDecimal.valueOf(10), BigDecimal.valueOf(9), BigDecimal.ZERO,
            BigDecimal.valueOf(15), BigDecimal.valueOf(5), BigDecimal.valueOf(30));
        return new RackLayout.Configuration(clearances, racks, CURRENT_DEMO_PALLET, true,
            CURRENT_DEMO_PALLET_RATINGS, racks.size(), "AUTO_BALANCED_TWO_ROWS");
    }

    /**
     * Detect a current-version automatic snapshot that still contains the old
     * hard-coded rack gaps.  This is needed because some records were saved
     * with the current rules version before the legacy normalizer was fixed.
     * Manual layouts are intentionally not inspected here.
     */
    private boolean hasDemoAisleMismatch(UnitType type, RackLayout.Configuration configuration) {
        if (!Boolean.TRUE.equals(configuration.autoGeneratePallet())
            || !"AUTO_BALANCED_TWO_ROWS".equalsIgnoreCase(configuration.layoutMode())) {
            return false;
        }

        List<RackLayout.Rack> racks = configuration.racks();
        if (racks == null || racks.size() < 2) return false;

        BigDecimal expectedRowGap = positiveOr(type.getStorageAisleWidthM(), new BigDecimal("2.5"))
            .movePointRight(2);
        BigDecimal expectedMainAisle = positiveOr(type.getAisleWidthM(), new BigDecimal("3"))
            .movePointRight(2);
        int racksPerRow = Math.max(1, (racks.size() + 1) / 2);
        RackLayout.Rack first = racks.get(0);
        boolean rotated = first.orientationDeg() != null
            && first.orientationDeg().compareTo(BigDecimal.valueOf(90)) == 0;
        BigDecimal alongLength = rotated ? first.widthCm() : first.lengthCm();
        BigDecimal acrossWidth = rotated ? first.lengthCm() : first.widthCm();

        RackLayout.Rack secondInRow = racks.get(1);
        BigDecimal actualRowGap = secondInRow.xCm().subtract(first.xCm()).subtract(alongLength);
        if (actualRowGap.compareTo(expectedRowGap) != 0) return true;

        if (racks.size() > racksPerRow) {
            RackLayout.Rack firstRackOfSecondRow = racks.get(racksPerRow);
            BigDecimal actualMainAisle = firstRackOfSecondRow.yCm()
                .subtract(first.yCm()).subtract(acrossWidth);
            return actualMainAisle.compareTo(expectedMainAisle) != 0;
        }
        return false;
    }

    private boolean isBuiltInDemoType(UnitType type) {
        return type.getCode() != null && BUILT_IN_DEMO_TYPE_CODES.contains(type.getCode().trim().toUpperCase())
            && type.getRackCount() > 0;
    }

    private UnitType unitType(UUID typeId) {
        return unitTypes.findById(typeId).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy loại gian kho."));
    }

    private void requireManager(ActorPrincipal actor, UUID facilityId) {
        if (!actor.hasRole(RoleCode.MANAGER)) throw ApiExceptions.forbidden("Chỉ Manager được quản lý cấu hình kệ.");
        authorization.require(actor, SystemPermission.MANAGE_INVENTORY);
        scopes.assertCanManage(actor, facilityId);
    }

    private void requireBusiness(ActorPrincipal actor) {
        if (!actor.hasRole(RoleCode.BUSINESS)) throw ApiExceptions.forbidden("Chỉ BO được xem cấu hình kệ tại đây.");
        authorization.require(actor, SystemPermission.VIEW_FACILITIES);
    }

    void requireEditable(UnitType type) {
        if (type.getStatus() == UnitTypeStatus.archived) {
            throw ApiExceptions.conflict("Loại kho đã được lưu trữ, không thể thay đổi cấu hình kệ.");
        }
        if (hasUnitsInUse(type)) {
            throw ApiExceptions.conflict("Loại kho còn gian được giữ chỗ, đã phân, đang thuê hoặc có đơn đang giữ chỗ/đã thanh toán cọc. Vui lòng chờ giải phóng trước khi thay đổi sức chứa.");
        }
    }

    private boolean hasUnitsInUse(UnitType type) {
        List<StorageUnitStatus> inUse = List.of(StorageUnitStatus.reserved, StorageUnitStatus.occupied,
            StorageUnitStatus.held, StorageUnitStatus.assigned);
        return capacity.hasLiveCapacityHold(type.getId())
            || !capacity.livePalletHoldUnitIds(type.getId()).isEmpty()
            || units.countByFacility_IdAndUnitType_IdAndStatusIn(type.getFacility().getId(), type.getId(), inUse) > 0;
    }
}
