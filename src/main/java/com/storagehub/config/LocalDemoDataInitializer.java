package com.storagehub.config;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.FacilityPolicyCatalog;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserFacilityScope;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.FacilityPolicyCatalogRepository;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.api.facility.RackLayout;
import com.storagehub.api.configuration.FacilityContentPolicy;
import com.storagehub.api.renewal.PublishedRenewalPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.RackLayoutService;
import com.storagehub.service.StorageHubRules;
import com.storagehub.service.renewal.integration.RenewalPolicyPublicationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
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
    private static final Set<String> PALLET_DEMO_FACILITIES = Set.of("BD-F01", "HCM-Q1-F01");
    // v6 demo pallet: it is supported by both beams and may overhang each side
    // by at most five centimetres.
    private static final RackLayout.Pallet DEMO_PALLET = new RackLayout.Pallet(
        210, 170, decimal(18), StorageHubRules.calculatePalletWeightKg(decimal(210), decimal(170)), decimal(500));
    /**
     * The catalog is keyed by generated geometry.  Keeping the two supported
     * layouts in the demo data makes the maximum feasible count visible to
     * Manager without allowing a rating for one size to leak into another.
     */
    private static final List<RackLayout.PalletRating> DEMO_PALLET_RATINGS = List.of(
        new RackLayout.PalletRating(210, 170, decimal(18),
            StorageHubRules.calculatePalletWeightKg(decimal(210), decimal(170)), decimal(500)),
        new RackLayout.PalletRating(210, 80, decimal(18),
            StorageHubRules.calculatePalletWeightKg(decimal(210), decimal(80)), decimal(500))
    );

    private final FacilityRepository facilityRepository;
    private final FacilityPolicyCatalogRepository facilityPolicyCatalogRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final RentalPackagePolicyRepository rentalPackagePolicyRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserFacilityScopeRepository userFacilityScopeRepository;
    private final PasswordEncoder passwordEncoder;
    private final RenewalPolicyPublicationService renewalPolicyPublicationService;
    private final RackLayoutService rackLayouts;
    private final ObjectMapper objectMapper;

    private static final String FACILITY_POLICY_CATALOG_ID = "facility-content-policies";

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
        int contentPolicyCount = seedFacilityContentPolicies();
        int userCount = seedUsers(facilities);
        int renewalPolicyCount = seedRenewalPolicies(facilities);

        log.info(
            "Local demo data ready: {} facilities, {} unit types, {} storage units, {} rental packages, {} content policies, {} demo users, {} renewal policies",
            facilities.size(), unitTypes.size(), unitCount, packageCount, contentPolicyCount, userCount, renewalPolicyCount
        );
    }

    /**
     * The read-only BO facility modal uses the same content-policy catalog as
     * Customer. Seed the existing demo regulations only when the local demo
     * database has never created that catalog; never overwrite BO changes.
     */
    private int seedFacilityContentPolicies() {
        var existing = facilityPolicyCatalogRepository.findById(FACILITY_POLICY_CATALOG_ID);
        if (existing.isPresent()) {
            try {
                List<?> current = objectMapper.readValue(existing.get().getContentJson(), List.class);
                if (current != null && !current.isEmpty()) return 0;
            } catch (Exception exception) {
                // Preserve a non-empty or malformed catalog for BO to repair.
                return 0;
            }
        }

        List<FacilityContentPolicy> policies = List.of(
            new FacilityContentPolicy(
                "demo-late-fee", "Phí quá hạn",
                "50% mỗi ngày, tính theo đơn giá thuê tháng chia 30 ngày.",
                "Toàn bộ cơ sở", false,
                "Áp dụng từ ngày kế tiếp ngày hết hạn đến khi bàn giao và nghiệm thu kho.",
                "2026-01-01", "all", List.of(), "billing"),
            new FacilityContentPolicy(
                "demo-deposit", "Quy định đặt cọc",
                "Tiền cọc đảm bảo kho theo loại kho; tiền giữ chỗ theo gói thuê, mặc định 20% và được trừ vào tiền thuê khi nhận kho.",
                "Toàn bộ cơ sở", false,
                "Tiền cọc được đối soát khi trả kho, sau khi kiểm tra hiện trạng và công nợ.",
                "2026-01-01", "all", List.of(), "billing"),
            new FacilityContentPolicy(
                "demo-minimum-term", "Quy định thời hạn thuê tối thiểu",
                "Thời hạn thuê tối thiểu là 01 tháng.",
                "Toàn bộ cơ sở", false,
                "Thời hạn thuê và ngày bắt đầu, ngày kết thúc được ghi trong hợp đồng.",
                "2026-01-01", "all", List.of(), "billing"),
            new FacilityContentPolicy(
                "demo-renewal", "Quy định gia hạn",
                "Khách hàng gửi yêu cầu trước ngày kết thúc hợp đồng; được tối đa 7 ngày để hoàn tất gia hạn sau ngày hết hạn.",
                "Toàn bộ cơ sở", false,
                "Từ ngày thứ 8, hồ sơ được chuyển sang quy trình xử lý quá hạn và thu hồi theo quy định.",
                "2026-01-01", "all", List.of(), "billing"),
            new FacilityContentPolicy(
                "demo-return", "Quy định trả kho",
                "Khách hàng thông báo trả kho, lấy tài sản, thanh toán công nợ và bàn giao để cơ sở nghiệm thu.",
                "Toàn bộ cơ sở", false,
                "Phí quá hạn và chi phí phát sinh được đối soát trong quyết toán; tiền cọc hoàn trả theo kết quả nghiệm thu.",
                "2026-01-01", "all", List.of(), "maintenance")
        );

        FacilityPolicyCatalog catalog = new FacilityPolicyCatalog();
        catalog.setId(FACILITY_POLICY_CATALOG_ID);
        try {
            catalog.setContentJson(objectMapper.writeValueAsString(policies));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot seed facility content policies", exception);
        }
        facilityPolicyCatalogRepository.saveAndFlush(catalog);
        return policies.size();
    }

    private Map<String, Facility> seedFacilities() {
        Map<String, Facility> result = new HashMap<>();
        facilityRepository.findAll().stream()
            .filter(facility -> facility.getStatus() == FacilityStatus.active)
            .forEach(facility -> result.put(facility.getCode(), facility));
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
                boolean palletDemo = PALLET_DEMO_FACILITIES.contains(facilityCode)
                    || (unitType != null && isSeedDemoType(unitType, definition));
                if (unitType != null) {
                    if (unitType.getRackLayoutJson() != null) {
                        if (palletDemo && isSeedDemoType(unitType, definition)) {
                            rackLayouts.upgradeLegacyDemoLayout(unitType.getId(), unitType.getVersion(),
                                demoRackLayout(definition));
                            rackLayouts.upgradeDemoLayout(unitType.getId(), unitType.getVersion(),
                                legacyDemoRackLayout(definition), demoRackLayout(definition));
                            rackLayouts.upgradeDemoLayout(unitType.getId(), unitType.getVersion(),
                                legacy120DemoRackLayout(definition), demoRackLayout(definition));
                            rackLayouts.upgradeDemoLayout(unitType.getId(), unitType.getVersion(),
                                legacyTwoPalletDemoRackLayout(definition, new RackLayout.Pallet(170, 164, decimal(14.4), decimal(25))),
                                demoRackLayout(definition));
                            rackLayouts.upgradeDemoLayout(unitType.getId(), unitType.getVersion(),
                                legacyTwoPalletDemoRackLayout(definition, new RackLayout.Pallet(120, 80, decimal(14.4), decimal(25))),
                                demoRackLayout(definition));
                            rackLayouts.upgradeDemoLayout(unitType.getId(), unitType.getVersion(),
                                previousDirectBeamDemoRackLayout(definition), demoRackLayout(definition));
                            // Each upgrade method locks and may update the same
                            // UnitType in its own transaction. Refresh the
                            // instance before applying the remaining demo
                            // fields, otherwise saveAndFlush merges a stale
                            // @Version value and aborts application startup.
                            unitType = unitTypeRepository.findById(unitType.getId()).orElse(unitType);
                            applyDemoDefinition(unitType, definition);
                            unitType = unitTypeRepository.saveAndFlush(unitType);
                        }
                        result.put(key, unitType);
                        continue;
                    }
                    if (palletDemo && !matchesDemoType(unitType, definition)) {
                        result.put(key, unitType);
                        continue;
                    }
                }
                if (unitType == null) {
                    unitType = new UnitType();
                    unitType.setFacility(facility);
                    unitType.setCode(definition.code());
                }
                unitType.setName(definition.name());
                unitType.setDescription(definition.description());
                unitType.setLengthM(decimal(definition.lengthM()));
                unitType.setWidthM(decimal(definition.widthM()));
                unitType.setHeightM(decimal(definition.heightM()));
                if (unitType.getMonthlyPrice() == null || unitType.getMonthlyPrice().signum() <= 0) unitType.setMonthlyPrice(decimal(definition.monthlyPrice()));
                unitType.setSecurityDepositAmount(BigDecimal.ZERO);
                unitType.setMaxLoadKg(decimal(definition.maxLoadKg()));
                unitType.setMaxStoredLoadKg(decimal(definition.maxStoredLoadKg()));
                unitType.setStorageAisleWidthM(decimal(definition.storageAisleWidthM()));
                unitType.setGateWidthM(decimal(definition.gateWidthM()));
                unitType.setGateHeightM(decimal(definition.gateHeightM()));
                unitType.setLowestObstructionHeightM(decimal(definition.lowestObstructionHeightM()));
                unitType.setOverheadClearanceM(decimal(definition.overheadClearanceM()));
                unitType.setRackCount(definition.rackCount());
                unitType.setRackLengthM(decimal(4.0));
                unitType.setRackWidthM(decimal(2.0));
                unitType.setRackHeightM(decimal(4.5));
                unitType.setAisleWidthM(decimal(definition.mainAisleWidthM()));
                unitType.setTrolleyType(definition.trolleyType());
                unitType.setSmallBoxCapacity(definition.smallBoxCapacity());
                unitType.setLargeBoxCapacity(definition.largeBoxCapacity());
                unitType.setImageUrl(definition.imageUrl());
                unitType.setStatus(UnitTypeStatus.active);
                if (palletDemo) rackLayouts.initializeDemoLayout(unitType, demoRackLayout(definition));
                unitType = unitTypeRepository.saveAndFlush(unitType);
                result.put(key, unitType);
            }
        }
        return result;
    }

    private boolean matchesDemoType(UnitType type, UnitTypeDefinition definition) {
        return type.getStatus() == UnitTypeStatus.active && definition.name().equals(type.getName())
            && decimal(definition.lengthM()).compareTo(type.getLengthM()) == 0
            && decimal(definition.widthM()).compareTo(type.getWidthM()) == 0
            && decimal(definition.heightM()).compareTo(type.getHeightM()) == 0
            && decimal(definition.maxLoadKg()).compareTo(type.getMaxLoadKg()) == 0
            && type.getMaxStoredLoadKg() != null
            && decimal(definition.maxStoredLoadKg()).compareTo(type.getMaxStoredLoadKg()) == 0
            && type.getRackCount() == definition.rackCount()
            && decimal(4).compareTo(type.getRackLengthM()) == 0
            && decimal(2).compareTo(type.getRackWidthM()) == 0
            && decimal(4.5).compareTo(type.getRackHeightM()) == 0;
    }

    private boolean isSeedDemoType(UnitType type, UnitTypeDefinition definition) {
        return type.getStatus() == UnitTypeStatus.active && definition.name().equals(type.getName())
            && type.getRackCount() == definition.rackCount()
            && decimal(4).compareTo(type.getRackLengthM()) == 0
            && decimal(2).compareTo(type.getRackWidthM()) == 0
            && decimal(4.5).compareTo(type.getRackHeightM()) == 0;
    }

    private void applyDemoDefinition(UnitType type, UnitTypeDefinition definition) {
        type.setLengthM(decimal(definition.lengthM()));
        type.setWidthM(decimal(definition.widthM()));
        type.setHeightM(decimal(definition.heightM()));
        type.setMaxLoadKg(decimal(definition.maxLoadKg()));
        type.setMaxStoredLoadKg(decimal(definition.maxStoredLoadKg()));
        type.setStorageAisleWidthM(decimal(definition.storageAisleWidthM()));
        type.setGateWidthM(decimal(definition.gateWidthM()));
        type.setGateHeightM(decimal(definition.gateHeightM()));
        type.setLowestObstructionHeightM(decimal(definition.lowestObstructionHeightM()));
        type.setOverheadClearanceM(decimal(definition.overheadClearanceM()));
        type.setRackCount(definition.rackCount());
        type.setRackLengthM(decimal(4));
        type.setRackWidthM(decimal(2));
        type.setRackHeightM(decimal(4.5));
        type.setAisleWidthM(decimal(definition.mainAisleWidthM()));
        type.setSmallBoxCapacity(definition.smallBoxCapacity());
        type.setLargeBoxCapacity(definition.largeBoxCapacity());
    }

    private RackLayout.Configuration demoRackLayout(UnitTypeDefinition definition) {
        // Two rows of demo racks face the main aisle. Coordinates follow the
        // v8 layout convention: the rack is rotated so its 200 cm width is
        // across the storage aisle and its 400 cm length runs along it.
        int racksPerRow = definition.rackCount() / 2;
        BigDecimal frameGap = decimal(definition.rackSpacingM()).movePointRight(2);
        BigDecimal mainAisle = decimal(definition.mainAisleWidthM()).movePointRight(2);
        BigDecimal firstX = decimal(30).add(frameGap).subtract(decimal(5));
        BigDecimal farRowY = decimal(30).add(decimal(400)).add(mainAisle);
        List<RackLayout.Rack> racks = IntStream.range(0, definition.rackCount())
            .mapToObj(index -> new RackLayout.Rack("R%02d".formatted(index + 1),
                decimal(400), decimal(200), decimal(450), firstX.add(frameGap.add(decimal(200)).multiply(decimal(index % racksPerRow))),
                index < racksPerRow ? decimal(30) : farRowY, decimal(90),
                decimal(3), decimal(8), decimal(3), decimal(15), decimal(15),
                decimal(1), decimal(100), decimal(1200), decimal(200), decimal(100)))
            .toList();
        var clearances = new RackLayout.Clearances(decimal(10), decimal(9), decimal(0), decimal(15), decimal(5), decimal(30));
        return new RackLayout.Configuration(clearances, racks, DEMO_PALLET, true, DEMO_PALLET_RATINGS);
    }

    private RackLayout.Configuration legacyDemoRackLayout(UnitTypeDefinition definition) {
        return legacyRackLayout(definition, new RackLayout.Pallet(170, 164, decimal(14.4), decimal(25)), 1);
    }

    private RackLayout.Configuration legacy120DemoRackLayout(UnitTypeDefinition definition) {
        return legacyRackLayout(definition, new RackLayout.Pallet(120, 80, decimal(14.4), decimal(25)), 1);
    }

    private RackLayout.Configuration legacyTwoPalletDemoRackLayout(UnitTypeDefinition definition, RackLayout.Pallet pallet) {
        return legacyRackLayout(definition, pallet, 2);
    }

    private RackLayout.Configuration previousDirectBeamDemoRackLayout(UnitTypeDefinition definition) {
        int racksPerRow = definition.rackCount() / 2;
        BigDecimal farRowY = decimal(30).add(decimal(200)).add(decimal(definition.rackSpacingM()).movePointRight(2));
        List<RackLayout.Rack> racks = IntStream.range(0, definition.rackCount())
            .mapToObj(index -> new RackLayout.Rack("R%02d".formatted(index + 1),
                decimal(400), decimal(200), decimal(450), decimal(400L * (index % racksPerRow)),
                index < racksPerRow ? decimal(30) : farRowY, BigDecimal.ZERO,
                decimal(3), decimal(8), decimal(3), decimal(15), decimal(15), decimal(1),
                decimal(100), decimal(1200), decimal(200), decimal(100)))
            .toList();
        var clearances = new RackLayout.Clearances(decimal(0), decimal(9), decimal(0), decimal(15), decimal(5), decimal(30));
        return new RackLayout.Configuration(clearances, racks, DEMO_PALLET);
    }

    private RackLayout.Configuration legacyRackLayout(UnitTypeDefinition definition, RackLayout.Pallet pallet, int palletsPerCompartment) {
        int racksPerRow = definition.rackCount() / 2;
        BigDecimal farRowY = decimal(definition.mainAisleWidthM()).movePointRight(2).add(decimal(200));
        List<RackLayout.Rack> racks = IntStream.range(0, definition.rackCount())
            .mapToObj(index -> new RackLayout.Rack("R%02d".formatted(index + 1),
                decimal(400), decimal(200), decimal(450), decimal(400L * (index % racksPerRow)),
                index < racksPerRow ? BigDecimal.ZERO : farRowY, BigDecimal.ZERO,
                decimal(3), decimal(8), decimal(3), decimal(15), decimal(15),
                decimal(palletsPerCompartment), decimal(100), decimal(1200)))
            .toList();
        return new RackLayout.Configuration(new RackLayout.Clearances(decimal(10), decimal(9), decimal(10), decimal(15)), racks, pallet);
    }

    private int seedStorageUnits(Map<String, Facility> facilities, Map<String, UnitType> unitTypes) {
        Map<String, StorageUnit> existing = new HashMap<>();
        storageUnitRepository.findAll().forEach(unit -> existing.put(unit.getCode(), unit));
        int createdCount = 0;

        for (String facilityCode : facilities.keySet()) {
            Facility facility = facilities.get(facilityCode);
            for (UnitTypeDefinition definition : unitTypeDefinitions()) {
                UnitType unitType = unitTypes.get(unitTypeKey(facilityCode, definition.code()));
                if (unitType.getStatus() != UnitTypeStatus.active) continue;
                boolean palletDemo = PALLET_DEMO_FACILITIES.contains(facilityCode);
                int physicalUnitCount = palletDemo ? definition.demoPhysicalUnitCount() : 5;
                for (int number = 1; number <= physicalUnitCount; number++) {
                    String code = "%s-%s-%03d".formatted(facilityCode, definition.code(), number);
                    if (existing.containsKey(code)) {
                        StorageUnit current = existing.get(code);
                        if (current.getRackLayoutJson() == null && unitType.getRackLayoutJson() != null) {
                            current.setRackLayoutJson(unitType.getRackLayoutJson());
                            storageUnitRepository.save(current);
                        }
                        continue;
                    }

                    StorageUnit unit = new StorageUnit();
                    unit.setFacility(facility);
                    unit.setUnitType(unitType);
                    unit.setCode(code);
                    unit.setFloor(String.valueOf(definition.floor()));
                    unit.setZone(definition.zone());
                    unit.setStatus(palletDemo || number == 1 ? StorageUnitStatus.available : StorageUnitStatus.inactive);
                    unit.setRackLayoutJson(unitType.getRackLayoutJson());
                    StorageUnit saved = storageUnitRepository.saveAndFlush(unit);
                    existing.put(saved.getCode(), saved);
                    createdCount++;
                }
            }
        }
        return existing.size();
    }

    private int seedRentalPackagePolicies(Map<String, Facility> facilities) {
        // Không tự động seed các gói demo DEMO_3M, DEMO_6M, DEMO_12M, DEMO_24M
        // để BO toàn quyền quản lý danh sách gói thuê thực tế
        return 0;
    }

    private int seedUsers(Map<String, Facility> facilities) {
        int seededCount = 0;
        for (DemoUserDefinition definition : List.of(
            new DemoUserDefinition("customer@storagehub.demo", "Demo Customer", "+84 908 123 456", RoleCode.CUSTOMER, "Customer@1234!", "FPT-TD-F01", FacilityScopeLevel.READ),
            new DemoUserDefinition("staff@storagehub.demo", "Demo Staff", "+84 905 550 101", RoleCode.STAFF, "Staff@1234!", "FPT-TD-F01", FacilityScopeLevel.OPERATE),
            new DemoUserDefinition("manager@storagehub.demo", "Demo Manager", "+84 903 444 888", RoleCode.MANAGER, "Manager@1234!", "FPT-TD-F01", FacilityScopeLevel.MANAGE),
            new DemoUserDefinition("business@storagehub.demo", "Demo Operations", "+84 28 3999 1111", RoleCode.BUSINESS, "Business@1234!", null, null),
            new DemoUserDefinition("admin@storagehub.demo", "StorageHub Admin", "+84 900 000 001", RoleCode.ADMIN, "Admin@1234!", null, null)
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
                if (facility != null) {
                    UserFacilityScope scope = userFacilityScopeRepository
                        .findByUser_IdAndFacility_Id(saved.getId(), facility.getId())
                        .orElseGet(UserFacilityScope::new);
                    scope.setUser(saved);
                    scope.setFacility(facility);
                    scope.setScopeLevel(definition.scopeLevel());
                    userFacilityScopeRepository.saveAndFlush(scope);
                }
            }
        }
        return seededCount;
    }

    private int seedRenewalPolicies(Map<String, Facility> facilities) {
        User business = userRepository.findByEmailIgnoreCase("business@storagehub.demo").orElse(null);
        if (business == null || facilities.isEmpty()) return 0;

        ActorPrincipal actor = new ActorPrincipal(
            business.getId(),
            java.util.UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of(SystemPermission.VIEW_POLICIES.code(), SystemPermission.MANAGE_POLICIES.code()),
            Map.of()
        );
        int createdCount = 0;
        for (Facility facility : facilities.values()) {
            if (renewalPolicyPublicationService.read(facility.getId(), null).isPresent()) continue;
            Set<java.util.UUID> packageIds = new HashSet<>(
                rentalPackagePolicyRepository.findByFacility_IdOrderByRentalMonthsAsc(facility.getId()).stream()
                    .filter(RentalPackagePolicy::isActive)
                    .map(RentalPackagePolicy::getId)
                    .toList()
            );
            if (packageIds.isEmpty()) continue;

            renewalPolicyPublicationService.publish(
                actor,
                facility.getId(),
                new PublishedRenewalPolicy.Input(
                    0L,
                    LocalDate.of(2026, 1, 1),
                    null,
                    0,
                    24,
                    0,
                    BigDecimal.ZERO,
                    packageIds,
                    new PublishedRenewalPolicy.Signing(120, 60),
                    com.storagehub.service.overdue.RenewalOverdueTimeline.policy()
                )
            );
            createdCount++;
        }
        return createdCount;
    }

    private List<UnitTypeDefinition> unitTypeDefinitions() {
        return List.of(
            new UnitTypeDefinition("S", "Kho Nhỏ (S)", "Phù hợp đồ gia dụng nhẹ và thùng carton", 12.0, 11.6, 5.0, 5_500_000, 600, 2500, 3.0, 2.5, 2.4, 3.0, 5.0, 0.3, 4, 2.5, "Xe đẩy tay / xe sàn nhỏ", 120, 40, "/images/customer-units/kho-s.jpg", 1, "Khu A", 1),
            new UnitTypeDefinition("M", "Kho Trung (M)", "Phù hợp thiết bị văn phòng và nội thất vừa", 17.3, 11.8, 5.0, 9_500_000, 1_200, 4000, 3.2, 2.7, 2.6, 3.2, 5.0, 0.3, 6, 2.7, "Xe đẩy sàn phẳng", 240, 80, "/images/customer-units/kho-m.jpg", 2, "Khu B", 2),
            new UnitTypeDefinition("L", "Kho Lớn (L)", "Phù hợp pallet hàng và tồn kho kinh doanh", 23.0, 12.0, 5.0, 15_000_000, 2_400, 6000, 3.4, 2.9, 2.8, 3.4, 5.0, 0.3, 8, 2.9, "Xe nâng tay pallet", 480, 160, "/images/customer-units/kho-l.jpg", 3, "Khu C", 3),
            new UnitTypeDefinition("XL", "Kho Rất Lớn (XL)", "Phù hợp máy móc, hàng nặng và pallet công nghiệp", 29.1, 12.2, 5.0, 22_500_000, 3_600, 8000, 3.6, 3.1, 3.0, 3.5, 5.0, 0.3, 10, 3.1, "Xe nâng điện dắt bộ", 720, 240, "/images/customer-units/kho-xl.jpg", 4, "Khu D", 4)
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

    private String unitTypeKey(String facilityCode, String unitTypeCode) {
        return facilityCode + ":" + unitTypeCode;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }

    private record FacilityDefinition(String code, String name, String address, String city, String phone,
                                      String accessHours, String imageUrl, boolean climateControlled,
                                      String securityDescription) {}

    private record RentalPackageDefinition(
        String code,
        String name,
        int rentalMonths,
        BigDecimal discountRate
    ) {}

    private record UnitTypeDefinition(
        String code,
        String name,
        String description,
        double lengthM,
        double widthM,
        double heightM,
        double monthlyPrice,
        double maxLoadKg,
        double maxStoredLoadKg,
        double mainAisleWidthM,
        double storageAisleWidthM,
        double gateWidthM,
        double gateHeightM,
        double lowestObstructionHeightM,
        double overheadClearanceM,
        int rackCount,
        double rackSpacingM,
        String trolleyType,
        int smallBoxCapacity,
        int largeBoxCapacity,
        String imageUrl,
        int floor,
        String zone,
        int demoPhysicalUnitCount
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
