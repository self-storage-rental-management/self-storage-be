package com.storagehub.service;

import com.storagehub.api.facility.CreateFacilityRequest;
import com.storagehub.api.facility.FacilityResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FacilityManagementService {

    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final RentalPackagePolicyRepository rentalPackagePolicyRepository;

    @Transactional
    public FacilityResponse createFacility(ActorPrincipal actor, CreateFacilityRequest request) {
        if (!actor.hasAnyRole(RoleCode.ADMIN, RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Chỉ Business Owner hoặc Admin mới có quyền tạo hoặc cập nhật cơ sở");
        }

        String code = request.code().trim().toUpperCase();
        Facility existing = facilityRepository.findAll().stream()
            .filter(f -> code.equalsIgnoreCase(f.getCode()))
            .findFirst()
            .orElse(null);

        Facility facility;
        if (existing == null) {
            facility = new Facility();
            facility.setCode(code);
        } else {
            UUID existingId = existing.getId();
            // Nếu cơ sở đã tồn tại, xóa các unit và unit_type cũ để đồng bộ cấu hình mới từ BO
            List<StorageUnit> oldUnits = storageUnitRepository.findAll().stream()
                .filter(u -> u.getFacility().getId().equals(existingId))
                .toList();
            storageUnitRepository.deleteAll(oldUnits);
            storageUnitRepository.flush();

            List<UnitType> oldTypes = unitTypeRepository.findAll().stream()
                .filter(ut -> ut.getFacility().getId().equals(existingId))
                .toList();
            unitTypeRepository.deleteAll(oldTypes);
            unitTypeRepository.flush();

            facility = existing;
        }

        facility.setName(request.name().trim());
        facility.setAddress(request.address().trim());
        facility.setCity(request.city().trim());
        facility.setStatus(request.status() != null ? request.status() : FacilityStatus.active);

        Facility saved = facilityRepository.saveAndFlush(facility);

        // Tạo các loại gian kho và số lượng gian kho theo đúng cấu hình của BO
        provisionUnitTypesAndUnits(saved, request.unitSpecs());

        // Đảm bảo các gói thuê (package policies) tiêu chuẩn có sẵn cho cơ sở
        provisionRentalPackages(saved);

        return new FacilityResponse(
            saved.getId(),
            saved.getCode(),
            saved.getName(),
            saved.getAddress(),
            saved.getCity(),
            saved.getStatus(),
            saved.getCreatedAt(),
            saved.getUpdatedAt()
        );
    }

    private void provisionUnitTypesAndUnits(Facility facility, List<CreateFacilityRequest.UnitSpecRequest> unitSpecs) {
        // Thông số mặc định chuẩn
        Map<String, DefaultSpec> defaults = Map.of(
            "S", new DefaultSpec("Kho Nhỏ (S)", 8.0, 10.0, 5.0, 5_500_000, 600, 4, 1, "Khu A"),
            "M", new DefaultSpec("Kho Trung (M)", 12.6, 10.4, 5.0, 9_500_000, 1_200, 6, 2, "Khu B"),
            "L", new DefaultSpec("Kho Lớn (L)", 18.3, 10.8, 5.0, 15_000_000, 2_400, 8, 3, "Khu C"),
            "XL", new DefaultSpec("Kho Rất Lớn (XL)", 25.0, 11.2, 5.0, 22_500_000, 3_600, 10, 4, "Khu D")
        );

        if (unitSpecs != null && !unitSpecs.isEmpty()) {
            // Dùng danh sách cấu hình chi tiết do BO gửi lên
            for (CreateFacilityRequest.UnitSpecRequest spec : unitSpecs) {
                String sizeCode = spec.sizeCode() != null ? spec.sizeCode().trim().toUpperCase() : "S";
                DefaultSpec def = defaults.getOrDefault(sizeCode, defaults.get("S"));

                String name = (spec.name() != null && !spec.name().isBlank()) ? spec.name().trim() : def.name();
                double lengthM = spec.lengthM() != null && spec.lengthM() > 0 ? spec.lengthM() : def.lengthM();
                double widthM = spec.widthM() != null && spec.widthM() > 0 ? spec.widthM() : def.widthM();
                double heightM = spec.heightM() != null && spec.heightM() > 0 ? spec.heightM() : def.heightM();
                double monthlyPrice = spec.monthlyPrice() != null && spec.monthlyPrice() > 0 ? spec.monthlyPrice() : def.monthlyPrice();
                double maxLoadKg = spec.maxLoadKg() != null && spec.maxLoadKg() > 0 ? spec.maxLoadKg() : def.maxLoadKg();
                int count = spec.count() != null ? spec.count() : 5;

                UnitType unitType = new UnitType();
                unitType.setFacility(facility);
                unitType.setCode(sizeCode);
                unitType.setName(name);
                unitType.setLengthM(BigDecimal.valueOf(lengthM));
                unitType.setWidthM(BigDecimal.valueOf(widthM));
                unitType.setHeightM(BigDecimal.valueOf(heightM));
                unitType.setMonthlyPrice(BigDecimal.valueOf(monthlyPrice));
                unitType.setMaxLoadKg(BigDecimal.valueOf(maxLoadKg));
                unitType.setRackCount(def.rackCount());
                unitType.setRackLengthM(BigDecimal.valueOf(4.0));
                unitType.setRackWidthM(BigDecimal.valueOf(2.0));
                unitType.setRackHeightM(BigDecimal.valueOf(4.5));
                unitType.setStatus(UnitTypeStatus.active);
                UnitType savedType = unitTypeRepository.saveAndFlush(unitType);

                // Sinh số lượng gian kho thực tế theo cấu hình của BO
                for (int number = 1; number <= count; number++) {
                    String unitCode = "%s-%s-%03d".formatted(facility.getCode(), sizeCode, number);
                    StorageUnit unit = new StorageUnit();
                    unit.setFacility(facility);
                    unit.setUnitType(savedType);
                    unit.setCode(unitCode);
                    unit.setFloor(String.valueOf(def.floor()));
                    unit.setZone(def.zone());
                    unit.setStatus(StorageUnitStatus.available);
                    storageUnitRepository.saveAndFlush(unit);
                }
            }
        } else {
            // Fallback nếu không có unitSpecs
            for (Map.Entry<String, DefaultSpec> entry : defaults.entrySet()) {
                String sizeCode = entry.getKey();
                DefaultSpec def = entry.getValue();

                UnitType unitType = new UnitType();
                unitType.setFacility(facility);
                unitType.setCode(sizeCode);
                unitType.setName(def.name());
                unitType.setLengthM(BigDecimal.valueOf(def.lengthM()));
                unitType.setWidthM(BigDecimal.valueOf(def.widthM()));
                unitType.setHeightM(BigDecimal.valueOf(def.heightM()));
                unitType.setMonthlyPrice(BigDecimal.valueOf(def.monthlyPrice()));
                unitType.setMaxLoadKg(BigDecimal.valueOf(def.maxLoadKg()));
                unitType.setRackCount(def.rackCount());
                unitType.setRackLengthM(BigDecimal.valueOf(4.0));
                unitType.setRackWidthM(BigDecimal.valueOf(2.0));
                unitType.setRackHeightM(BigDecimal.valueOf(4.5));
                unitType.setStatus(UnitTypeStatus.active);
                UnitType savedType = unitTypeRepository.saveAndFlush(unitType);

                for (int number = 1; number <= 5; number++) {
                    String unitCode = "%s-%s-%03d".formatted(facility.getCode(), sizeCode, number);
                    StorageUnit unit = new StorageUnit();
                    unit.setFacility(facility);
                    unit.setUnitType(savedType);
                    unit.setCode(unitCode);
                    unit.setFloor(String.valueOf(def.floor()));
                    unit.setZone(def.zone());
                    unit.setStatus(StorageUnitStatus.available);
                    storageUnitRepository.saveAndFlush(unit);
                }
            }
        }
    }

    private void provisionRentalPackages(Facility facility) {
        List<RentalPackagePolicy> existing = rentalPackagePolicyRepository.findByFacility_IdOrderByRentalMonthsAsc(facility.getId());
        if (existing.isEmpty()) {
            List<RentalPackagePolicy> policies = List.of(
                createPackage(facility, "ONE_MONTH", "Gói thuê 1 tháng", 1, BigDecimal.ZERO),
                createPackage(facility, "THREE_MONTHS", "Gói thuê 3 tháng (Tiết kiệm 5%)", 3, new BigDecimal("0.05")),
                createPackage(facility, "SIX_MONTHS", "Gói thuê 6 tháng (Tiết kiệm 10%)", 6, new BigDecimal("0.10")),
                createPackage(facility, "TWELVE_MONTHS", "Gói thuê 12 tháng (Tiết kiệm 15%)", 12, new BigDecimal("0.15")),
                createPackage(facility, "PKG-1M", "Gói thuê 1 tháng", 1, BigDecimal.ZERO),
                createPackage(facility, "PKG-3M", "Gói thuê 3 tháng", 3, new BigDecimal("0.05")),
                createPackage(facility, "PKG-6M", "Gói thuê 6 tháng", 6, new BigDecimal("0.10")),
                createPackage(facility, "PKG-12M", "Gói thuê 12 tháng", 12, new BigDecimal("0.15"))
            );
            rentalPackagePolicyRepository.saveAll(policies);
        }
    }

    private RentalPackagePolicy createPackage(Facility facility, String code, String name, int months, BigDecimal discountRate) {
        RentalPackagePolicy p = new RentalPackagePolicy();
        p.setFacility(facility);
        p.setCode(code);
        p.setName(name);
        p.setRentalMonths(months);
        p.setDiscountRate(discountRate);
        p.setPolicyVersion("v1.0");
        p.setActive(true);
        p.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return p;
    }

    private record DefaultSpec(
        String name,
        double lengthM,
        double widthM,
        double heightM,
        double monthlyPrice,
        double maxLoadKg,
        int rackCount,
        int floor,
        String zone
    ) {}
}
