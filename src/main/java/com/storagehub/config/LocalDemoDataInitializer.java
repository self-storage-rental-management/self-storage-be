package com.storagehub.config;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserFacilityScope;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.domain.repo.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@ConditionalOnProperty(prefix = "app.bootstrap-demo-data", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LocalDemoDataInitializer {

    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final RentalPackagePolicyRepository rentalPackagePolicyRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserFacilityScopeRepository userFacilityScopeRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    @Order(20)
    CommandLineRunner seedLocalDemoData() {
        return args -> seed();
    }

    private void seed() {
        Map<String, Facility> facilities = seedFacilities();
        Map<String, UnitType> unitTypes = seedUnitTypes(facilities);
        int unitCount = seedStorageUnits(facilities, unitTypes);
        int packageCount = seedRentalPackagePolicies(facilities);
        int userCount = seedUsers(facilities);

        log.info(
            "Local demo data ready: {} facilities, {} unit types, {} storage units, {} rental packages, {} demo users",
            facilities.size(), unitTypes.size(), unitCount, packageCount, userCount
        );
    }
   

    private Map<String, Facility> seedFacilities() {
        Map<String, Facility> result = new HashMap<>();
        for (FacilityDefinition definition : List.of(
            new FacilityDefinition(
                "HCM-Q1-F01",
                "Kho Việt – Cơ sở Quận 1",
                "125 Nguyễn Bỉnh Khiêm, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh",
                "TP. Hồ Chí Minh"
            ),
            new FacilityDefinition(
                "BD-F01",
                "Kho Việt – Cơ sở Bình Dương",
                "468 Đại lộ Bình Dương, Phường Lái Thiêu, TP. Thuận An, Bình Dương",
                "Bình Dương"
            )
        )) {
            Facility facility = facilityRepository.findAll().stream()
                .filter(item -> definition.code().equals(item.getCode()))
                .findFirst()
                .orElseGet(() -> {
                    Facility created = new Facility();
                    created.setCode(definition.code());
                    created.setName(definition.name());
                    created.setAddress(definition.address());
                    created.setCity(definition.city());
                    created.setStatus(FacilityStatus.active);
                    return facilityRepository.saveAndFlush(created);
                });
            result.put(facility.getCode(), facility);
        }
        return result;
    }

    private Map<String, UnitType> seedUnitTypes(Map<String, Facility> facilities) {
        Map<String, UnitType> result = new HashMap<>();
        for (String facilityCode : facilities.keySet()) {
            Facility facility = facilities.get(facilityCode);
            for (UnitTypeDefinition definition : unitTypeDefinitions()) {
                String key = unitTypeKey(facilityCode, definition.code());
                UnitType unitType = unitTypeRepository
                    .findByFacility_IdAndCode(facility.getId(), definition.code())
                    .orElse(null);
                if (unitType == null) {
                    unitType = new UnitType();
                    unitType.setFacility(facility);
                    unitType.setCode(definition.code());
                    unitType.setName(definition.name());
                    unitType.setLengthM(decimal(definition.lengthM()));
                    unitType.setWidthM(decimal(definition.widthM()));
                    unitType.setHeightM(decimal(definition.heightM()));
                    unitType.setMonthlyPrice(decimal(definition.monthlyPrice()));
                    unitType.setMaxLoadKg(decimal(definition.maxLoadKg()));
                    unitType.setRackCount(definition.rackCount());
                    unitType.setRackLengthM(decimal(4.0));
                    unitType.setRackWidthM(decimal(2.0));
                    unitType.setRackHeightM(decimal(4.5));
                    unitType.setStatus(UnitTypeStatus.active);
                    unitType = unitTypeRepository.saveAndFlush(unitType);
                }
                result.put(key, unitType);
            }
        }
        return result;
    }

    private int seedStorageUnits(Map<String, Facility> facilities, Map<String, UnitType> unitTypes) {
        Map<String, StorageUnit> existing = new HashMap<>();
        storageUnitRepository.findAll().forEach(unit -> existing.put(unit.getCode(), unit));
        int createdCount = 0;

        for (String facilityCode : facilities.keySet()) {
            Facility facility = facilities.get(facilityCode);
            for (UnitTypeDefinition definition : unitTypeDefinitions()) {
                UnitType unitType = unitTypes.get(unitTypeKey(facilityCode, definition.code()));
                for (int number = 1; number <= 5; number++) {
                    String code = "%s-%s-%03d".formatted(facilityCode, definition.code(), number);
                    if (existing.containsKey(code)) {
                        continue;
                    }

                    StorageUnit unit = new StorageUnit();
                    unit.setFacility(facility);
                    unit.setUnitType(unitType);
                    unit.setCode(code);
                    unit.setFloor(String.valueOf(definition.floor()));
                    unit.setZone(definition.zone());
                    unit.setStatus(isOccupied(facilityCode, definition.code(), number)
                        ? StorageUnitStatus.occupied
                        : StorageUnitStatus.available);
                    StorageUnit saved = storageUnitRepository.saveAndFlush(unit);
                    existing.put(saved.getCode(), saved);
                    createdCount++;
                }
            }
        }
        return existing.size();
    }

    private int seedRentalPackagePolicies(Map<String, Facility> facilities) {
        int createdCount = 0;
        LocalDate effectiveFrom = LocalDate.of(2020, 1, 1);
        for (Facility facility : facilities.values()) {
            for (RentalPackageDefinition definition : rentalPackageDefinitions()) {
                if (rentalPackagePolicyRepository.findByFacility_IdAndCode(facility.getId(), definition.code()).isPresent()) {
                    continue;
                }

                RentalPackagePolicy policy = new RentalPackagePolicy();
                policy.setFacility(facility);
                policy.setCode(definition.code());
                policy.setName(definition.name());
                policy.setRentalMonths(definition.rentalMonths());
                policy.setDiscountRate(definition.discountRate());
                policy.setPolicyVersion("demo-v1");
                policy.setActive(true);
                policy.setEffectiveFrom(effectiveFrom);
                rentalPackagePolicyRepository.saveAndFlush(policy);
                createdCount++;
            }
        }
        return createdCount;
    }

    private int seedUsers(Map<String, Facility> facilities) {
        int seededCount = 0;
        for (DemoUserDefinition definition : List.of(
            new DemoUserDefinition("customer@storagehub.demo", "Demo Customer", "+84 908 123 456", RoleCode.CUSTOMER, "Customer@1234!", "HCM-Q1-F01", FacilityScopeLevel.READ),
            new DemoUserDefinition("staff@storagehub.demo", "Demo Staff", "+84 905 550 101", RoleCode.STAFF, "Staff@1234!", "HCM-Q1-F01", FacilityScopeLevel.OPERATE),
            new DemoUserDefinition("manager@storagehub.demo", "Demo Manager", "+84 903 444 888", RoleCode.MANAGER, "Manager@1234!", "HCM-Q1-F01", FacilityScopeLevel.MANAGE),
            new DemoUserDefinition("business@storagehub.demo", "Demo Operations", "+84 28 3999 1111", RoleCode.BUSINESS, "Business@1234!", null, null),
            new DemoUserDefinition("admin@storagehub.demo", "StorageHub Admin", "+84 900 000 001", RoleCode.ADMIN, "Admin@1234!", null, null),
            new DemoUserDefinition("thanhthao725218@gmail.com", "Thanh Thao", "+84 900 000 002", RoleCode.CUSTOMER, "Customer@1234!", null, null),
            new DemoUserDefinition("staff.bd@storagehub.demo", "Staff Binh Duong", "+84 900 000 003", RoleCode.STAFF, "Staff@1234!", "BD-F01", FacilityScopeLevel.OPERATE),
            new DemoUserDefinition("staff.q1@storagehub.demo", "Staff Quan 1", "+84 900 000 004", RoleCode.STAFF, "Staff@1234!", "HCM-Q1-F01", FacilityScopeLevel.OPERATE),
            new DemoUserDefinition("manager.bd@storagehub.demo", "Manager Binh Duong", "+84 900 000 005", RoleCode.MANAGER, "Manager@1234!", "BD-F01", FacilityScopeLevel.MANAGE),
            new DemoUserDefinition("manager.q1@storagehub.demo", "Manager Quan 1", "+84 900 000 006", RoleCode.MANAGER, "Manager@1234!", "HCM-Q1-F01", FacilityScopeLevel.MANAGE),
            new DemoUserDefinition("bo@storagehub.demo", "Business Owner", "+84 900 000 007", RoleCode.BUSINESS, "Business@1234!", null, null)
        )) {
            Role role = roleRepository.findByCode(definition.role())
                .orElseThrow(() -> new IllegalStateException("Role has not been initialized: " + definition.role()));
            User user = userRepository.findByEmailIgnoreCase(definition.email()).orElseGet(User::new);
            boolean newUser = user.getId() == null;
            user.setEmail(definition.email());
            user.setFullName(definition.fullName());
            user.setPhone(definition.phone());
            user.setPasswordHash(passwordEncoder.encode(definition.password()));
            user.setStatus(UserStatus.ACTIVE);
            user.setMustChangePassword(false);
            user.setRoles(new HashSet<>(Set.of(role)));
            User saved = userRepository.saveAndFlush(user);
            if (newUser) {
                seededCount++;
            }

            if (definition.facilityCode() != null) {
                Facility facility = facilities.get(definition.facilityCode());
                UserFacilityScope scope = userFacilityScopeRepository
                    .findByUser_IdAndFacility_Id(saved.getId(), facility.getId())
                    .orElseGet(UserFacilityScope::new);
                scope.setUser(saved);
                scope.setFacility(facility);
                scope.setScopeLevel(definition.scopeLevel());
                userFacilityScopeRepository.saveAndFlush(scope);
            }
        }
        return seededCount;
    }

    private List<UnitTypeDefinition> unitTypeDefinitions() {
        return List.of(
            new UnitTypeDefinition("S", "Kho Nhỏ (S)", 8.0, 10.0, 5.0, 5_500_000, 600, 4, 1, "Khu A"),
            new UnitTypeDefinition("M", "Kho Trung (M)", 12.6, 10.4, 5.0, 9_500_000, 1_200, 6, 2, "Khu B"),
            new UnitTypeDefinition("L", "Kho Lớn (L)", 18.3, 10.8, 5.0, 15_000_000, 2_400, 8, 3, "Khu C"),
            new UnitTypeDefinition("XL", "Kho Rất Lớn (XL)", 25.0, 11.2, 5.0, 22_500_000, 3_600, 10, 4, "Khu D")
        );
    }

    private List<RentalPackageDefinition> rentalPackageDefinitions() {
        return List.of(
            new RentalPackageDefinition("DEMO_3M", "Gói thuê 3 tháng", 3, BigDecimal.ZERO),
            new RentalPackageDefinition("DEMO_6M", "Gói thuê 6 tháng", 6, new BigDecimal("0.05")),
            new RentalPackageDefinition("DEMO_12M", "Gói thuê 12 tháng", 12, new BigDecimal("0.10")),
            new RentalPackageDefinition("DEMO_24M", "Gói thuê 24 tháng", 24, new BigDecimal("0.15"))
        );
    }

    private boolean isOccupied(String facilityCode, String unitTypeCode, int number) {
        if ("BD-F01".equals(facilityCode) && "XL".equals(unitTypeCode)) {
            return number <= 2;
        }
        return number == 1 && !"XL".equals(unitTypeCode);
    }

    private String unitTypeKey(String facilityCode, String unitTypeCode) {
        return facilityCode + ":" + unitTypeCode;
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }

    private record FacilityDefinition(String code, String name, String address, String city) {}

    private record RentalPackageDefinition(
        String code,
        String name,
        int rentalMonths,
        BigDecimal discountRate
    ) {}

    private record UnitTypeDefinition(
        String code,
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

    private record DemoUserDefinition(
        String email,
        String fullName,
        String phone,
        RoleCode role,
        String password,
        String facilityCode,
        FacilityScopeLevel scopeLevel
    ) {}
}
