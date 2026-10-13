package com.storagehub.service;

import com.storagehub.api.configuration.UnitTypePricingRequest;
import com.storagehub.api.facility.FacilityResponse;
import com.storagehub.api.facility.ManagerUnitTypeRequest;
import com.storagehub.api.facility.UnitTypeResponse;
import com.storagehub.api.facility.UpdateFacilityRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
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
    private final FacilityRepository facilities;
    private final UnitTypeRepository unitTypes;
    private final StorageUnitRepository units;
    private final RentalPackagePolicyRepository packages;
    private final FacilityScopeService scopes;
    private final AdminAuthorizationService authorization;
    private final AuditLogService audit;
    private final UserFacilityScopeRepository userFacilityScopes;

    @Transactional
    public FacilityResponse updateFacility(ActorPrincipal actor, UUID facilityId, UpdateFacilityRequest request) {
        requireBusiness(actor);
        Facility facility = facility(facilityId);
        FacilityResponse before = facilityResponse(facility);
        facility.setName(request.name().trim());
        facility.setAddress(request.address().trim());
        facility.setCity(request.city().trim());
        facility.setPhone(clean(request.phone()));
        facility.setAccessHours(clean(request.accessHours()));
        facility.setImageUrl(clean(request.imageUrl()));
        if (request.climateControlled() != null) facility.setClimateControlled(request.climateControlled());
        facility.setSecurityDescription(clean(request.securityDescription()));
        Facility saved = facilities.save(facility);
        FacilityResponse after = facilityResponse(saved);
        audit.recordMutation("FACILITY_UPDATED", "Facility", saved.getId(), saved.getId(), before, after);
        return after;
    }

    @Transactional
    public UnitTypeResponse createUnitType(ActorPrincipal actor, UUID facilityId, ManagerUnitTypeRequest request) {
        requireManager(actor, facilityId);
        Facility facility = facility(facilityId);
        if (facility.getStatus() == FacilityStatus.inactive) {
            throw ApiExceptions.conflict("Inactive facility cannot receive unit types");
        }
        String code = request.code().trim().toUpperCase();
        if (unitTypes.findByFacility_IdAndCode(facilityId, code).isPresent()) {
            throw ApiExceptions.conflict("Unit type code already exists in this facility");
        }
        UnitType type = new UnitType();
        type.setFacility(facility);
        applyTechnical(type, request);
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
        UnitType type = unitTypes.findById(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UUID facilityId = type.getFacility().getId();
        requireManager(actor, facilityId);
        UnitTypeResponse before = unitTypeResponse(type);
        applyTechnical(type, request);
        if (type.getStatus() == UnitTypeStatus.active) {
            type.setStatus(UnitTypeStatus.awaiting_pricing);
        }
        UnitType saved = unitTypes.save(type);
        UnitTypeResponse after = unitTypeResponse(saved);
        audit.recordMutation("UNIT_TYPE_UPDATED_BY_MANAGER", "UnitType", saved.getId(), facilityId, before, after);
        return after;
    }

    @Transactional
    public UnitTypeResponse priceUnitType(ActorPrincipal actor, UUID unitTypeId, UnitTypePricingRequest request) {
        requireBusiness(actor);
        UnitType type = unitTypes.findById(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UnitTypeResponse before = unitTypeResponse(type);
        type.setMonthlyPrice(request.monthlyPrice());
        type.setSecurityDepositAmount(request.securityDepositAmount());
        if (type.getStatus() == UnitTypeStatus.draft) type.setStatus(UnitTypeStatus.awaiting_pricing);
        UnitType saved = unitTypes.save(type);
        UnitTypeResponse after = unitTypeResponse(saved);
        audit.recordMutation("UNIT_TYPE_PRICED_BY_BUSINESS", "UnitType", saved.getId(), saved.getFacility().getId(), before, after);
        return after;
    }

    @Transactional
    public FacilityResponse publish(ActorPrincipal actor, UUID facilityId) {
        requireBusiness(actor);
        Facility facility = facility(facilityId);
        List<UnitType> candidates = unitTypes.findByFacility_IdOrderByCodeAsc(facilityId).stream()
            .filter(type -> type.getStatus() == UnitTypeStatus.awaiting_pricing || type.getStatus() == UnitTypeStatus.active)
            .toList();
        if (candidates.isEmpty()) throw ApiExceptions.conflict("Manager must define at least one unit type before publication");
        for (UnitType type : candidates) {
            if (type.getMonthlyPrice() == null || type.getMonthlyPrice().signum() <= 0) {
                throw ApiExceptions.conflict("Every published unit type must have an effective monthly price");
            }
            if (units.countByFacility_IdAndUnitType_IdAndStatus(facilityId, type.getId(), StorageUnitStatus.available) <= 0) {
                throw ApiExceptions.conflict("Every published unit type must have available physical inventory");
            }
        }
        boolean hasEffectivePackage = packages
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(facilityId, LocalDate.now())
            .stream().anyMatch(p -> p.getEffectiveTo() == null || !p.getEffectiveTo().isBefore(LocalDate.now()));
        if (!hasEffectivePackage) throw ApiExceptions.conflict("BO must configure an effective rental package before publication");

        FacilityResponse before = facilityResponse(facility);
        candidates.forEach(type -> type.setStatus(UnitTypeStatus.active));
        unitTypes.saveAll(candidates);
        facility.setStatus(FacilityStatus.active);
        Facility saved = facilities.save(facility);
        FacilityResponse after = facilityResponse(saved);
        audit.recordMutation("FACILITY_PUBLISHED", "Facility", facilityId, facilityId, before, after);
        return after;
    }

    private void applyTechnical(UnitType type, ManagerUnitTypeRequest request) {
        type.setName(request.name().trim());
        type.setLengthM(request.lengthM());
        type.setWidthM(request.widthM());
        type.setHeightM(request.heightM());
        type.setMaxLoadKg(request.maxLoadKg());
        type.setRackCount(request.rackCount());
        type.setRackLengthM(request.rackLengthM());
        type.setRackWidthM(request.rackWidthM());
        type.setRackHeightM(request.rackHeightM());
        type.setImageUrl(request.imageUrl() == null || request.imageUrl().isBlank() ? null : request.imageUrl().trim());
    }

    private void createPhysicalUnits(UnitType type, int count, String floor, String zone) {
        for (int index = 1; index <= count; index++) {
            StorageUnit unit = new StorageUnit();
            unit.setFacility(type.getFacility());
            unit.setUnitType(type);
            unit.setCode("%s-%s-%03d".formatted(type.getFacility().getCode(), type.getCode(), index));
            unit.setFloor(floor);
            unit.setZone(zone);
            unit.setStatus(StorageUnitStatus.available);
            units.save(unit);
        }
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
        return new UnitTypeResponse(t.getId(), t.getFacility().getId(), t.getCode(), t.getName(), t.getLengthM(), t.getWidthM(), t.getHeightM(),
            t.getAreaM2(), t.getVolumeM3(), t.getMonthlyPrice(), t.getSecurityDepositAmount(), t.getImageUrl(), t.getMaxLoadKg(),
            t.getRackCount(), t.getRackLengthM(), t.getRackWidthM(), t.getRackHeightM(), t.getStatus(), available, t.getCreatedAt(), t.getUpdatedAt());
    }
}
