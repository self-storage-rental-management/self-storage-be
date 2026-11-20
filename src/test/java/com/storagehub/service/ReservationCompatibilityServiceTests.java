package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.storagehub.api.facility.RackLayout;
import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.ReservationRecommendationRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationCompatibilityServiceTests {

    @Mock
    private FacilityRepository facilityRepository;

    @Mock
    private UnitTypeRepository unitTypeRepository;

    @Mock
    private ReservationCapacityService capacityService;

    @Mock
    private RentalPackagePolicyRepository policyRepository;

    @Mock
    private GoodsCatalogService goodsCatalogService;
    @Mock private StorageUnitRepository units;
    @Mock private RackLayoutService layouts;

    private ReservationCompatibilityService service;
    private PalletInventoryService palletInventory;
    private Facility facility;
    private UnitType unitType;
    private ActorPrincipal customer;

    @BeforeEach
    void setUp() {
        palletInventory = new PalletInventoryService(units, layouts, capacityService);
        service = new ReservationCompatibilityService(facilityRepository, unitTypeRepository, null,
            capacityService, null, palletInventory);
        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        facility.setStatus(FacilityStatus.active);

        unitType = new UnitType();
        ReflectionTestUtils.setField(unitType, "id", UUID.randomUUID());
        unitType.setFacility(facility);
        unitType.setStatus(UnitTypeStatus.active);
        unitType.setLengthM(new BigDecimal("6"));
        unitType.setWidthM(new BigDecimal("2"));
        unitType.setHeightM(new BigDecimal("5"));
        unitType.setMaxLoadKg(new BigDecimal("1000"));
        unitType.setRackCount(4);
        unitType.setRackLengthM(new BigDecimal("2"));
        unitType.setRackWidthM(new BigDecimal("4"));
        unitType.setRackHeightM(new BigDecimal("4.5"));

        customer = new ActorPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );
    }

    @Test
    void returnsCompatibleWhenGoodsFitAndCapacityExists() {
        stubAvailableUnitType(2);
        CompatibilityCheckRequest request = request(GoodsCategory.FURNITURE, null, null);

        var response = service.check(customer, request);

        assertThat(response.getResult()).isEqualTo(CompatibilityResult.COMPATIBLE);
        assertThat(response.getTotalGoodsVolumeM3()).isEqualByComparingTo("0.180000");
        assertThat(response.getTotalGoodsWeightKg()).isEqualByComparingTo("36.00");
        assertThat(response.getPalletPlan().requiredPallets()).isEqualTo(2);
        assertThat(response.getPalletPlan().availableSlots()).isEqualTo(12);
        assertThat(response.getAvailableUnitCount()).isEqualTo(2);
        assertThat(response.getIssues()).isEmpty();
    }

    @Test
    void acceptsElectronicsWithoutPrimaryMaterial() {
        stubAvailableUnitType(1);
        CompatibilityCheckRequest request = request(GoodsCategory.ELECTRONICS, null, null);
        request.getGoodsItems().getFirst().setMaterialName(null);

        var response = service.check(customer, request);

        assertThat(response.getResult()).isEqualTo(CompatibilityResult.COMPATIBLE);
    }

    @Test
    void requiresStaffReviewForValidOtherGoods() {
        stubAvailableUnitType(1);
        CompatibilityCheckRequest request = request(GoodsCategory.OTHER, "Custom item", null);

        var response = service.check(customer, request);

        assertThat(response.getResult()).isEqualTo(CompatibilityResult.REVIEW_REQUIRED);
        assertThat(response.isStaffReviewRequired()).isTrue();
    }

    @Test
    void rejectsOtherGoodsWithoutCustomFields() {
        stubAvailableUnitType(1);
        CompatibilityCheckRequest request = request(GoodsCategory.OTHER, null, null);

        assertThatThrownBy(() -> service.check(customer, request))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("customGoodsName");
    }

    @Test
    void combinesDifferentGoodsTypesIntoSharedRackCapacity() {
        stubAvailableUnitType(1);
        GoodsItemRequest first = goodsItem(GoodsCategory.FURNITURE, "Desk", 1,
            new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("50"));
        GoodsItemRequest second = goodsItem(GoodsCategory.ELECTRONICS, "Television", 1,
            new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("50"));
        CompatibilityCheckRequest request = new CompatibilityCheckRequest(
            facility.getId(), unitType.getId(), LocalDate.now().plusDays(1),
            LocalDate.now().plusMonths(3), "Packed", List.of(first, second)
        );

        var response = service.check(customer, request);

        assertThat(response.getTotalGoodsVolumeM3()).isEqualByComparingTo("0.600000");
        assertThat(response.getPalletPlan().requiredPallets()).isEqualTo(2);
        assertThat(response.getResult()).isEqualTo(CompatibilityResult.COMPATIBLE);
    }

    @Test
    void multipliesQuantityBeforeRoundingSmallPackageVolume() {
        stubAvailableUnitType(1);
        GoodsItemRequest item = goodsItem(GoodsCategory.FURNITURE, "Small items", 3,
            new BigDecimal("1.1"), new BigDecimal("1.1"), new BigDecimal("1.1"));
        var request = new CompatibilityCheckRequest(
            facility.getId(), unitType.getId(), LocalDate.now().plusDays(1),
            LocalDate.now().plusMonths(3), "Packed", List.of(item)
        );
        assertThat(service.check(customer, request).getTotalGoodsVolumeM3()).isEqualByComparingTo("0.000004");
    }

    @Test
    void calculatesTenCustomerPackagesAsOneCubicMeterAnd240Kg() {
        stubAvailableUnitType(1);
        GoodsItemRequest item = goodsItem(GoodsCategory.FURNITURE, "Packed goods", 10,
            new BigDecimal("40"), new BigDecimal("50"), new BigDecimal("50"));
        item.setWeightPerItemKg(new BigDecimal("24"));
        var request = new CompatibilityCheckRequest(
            facility.getId(), unitType.getId(), LocalDate.now().plusDays(1),
            LocalDate.now().plusMonths(3), "Packed", List.of(item)
        );
        var response = service.check(customer, request);
        assertThat(response.getTotalGoodsVolumeM3()).isEqualByComparingTo("1.000000");
        assertThat(response.getTotalGoodsWeightKg()).isEqualByComparingTo("240.00");
    }

    @Test
    void recommendsSmallestCompatibleUnitAndPreservesReviewRequiredResult() {
        var larger = unitType("LARGE", new BigDecimal("7"));
        var smaller = unitType("SMALL", new BigDecimal("6"));
        var policy = policy(3, null);
        var recommendation = recommendationService();
        stubRecommendation(policy, List.of(larger, smaller), 2);

        var response = recommendation.recommend(customer, recommendationRequest(GoodsCategory.FURNITURE));

        assertThat(response.recommendedUnitTypeId()).isEqualTo(smaller.getId());
        assertThat(response.result()).isEqualTo(CompatibilityResult.COMPATIBLE);
        assertThat(response.evaluatedUnitTypeIds()).containsExactly(larger.getId(), smaller.getId());
        assertThat(response.incompatibleUnitTypeIds()).isEmpty();
    }

    @Test
    void recommendationReturnsReviewRequiredForOtherGoodsInsteadOfRejectingIt() {
        var policy = policy(3, null);
        var recommendation = recommendationService();
        stubRecommendation(policy, List.of(unitType("OTHER", new BigDecimal("6"))), 1);

        var response = recommendation.recommend(customer, recommendationRequest(GoodsCategory.OTHER));

        assertThat(response.result()).isEqualTo(CompatibilityResult.REVIEW_REQUIRED);
        assertThat(response.recommendedUnitTypeId()).isNotNull();
    }

    @Test
    void recommendationReportsNoCapacityAndNoCompatibleUnit() {
        var policy = policy(3, null);
        var recommendation = recommendationService();
        var full = unitType("FULL", new BigDecimal("7"));
        stubRecommendation(policy, List.of(full), 0);
        stubPalletLayout(full, 0);

        var response = recommendation.recommend(customer, recommendationRequest(GoodsCategory.FURNITURE));

        assertThat(response.recommendedUnitTypeId()).isNull();
        assertThat(response.result()).isEqualTo(CompatibilityResult.INCOMPATIBLE);
        assertThat(response.issues()).anyMatch(issue -> issue.contains("Không còn gian kho trống"));
    }

    @Test
    void rejectsGoodsOutsidePalletEvenThoughTheyFitLegacyRackDimensions() {
        stubAvailableUnitType(1);
        var request = request(GoodsCategory.FURNITURE, null, null);
        var item = request.getGoodsItems().getFirst();
        item.setLengthCm(new BigDecimal("130"));
        item.setWidthCm(new BigDecimal("100"));
        var result = service.check(customer, request);
        assertThat(result.getResult()).isEqualTo(CompatibilityResult.INCOMPATIBLE);
        assertThat(result.getIssues()).anyMatch(issue -> issue.contains("không vừa mặt pallet"));
    }

    @Test
    void recommendsOnlyATypeWithAnAvailableFittingLayout() {
        var smaller = unitType("SMALL", new BigDecimal("6"));
        var larger = unitType("LARGE", new BigDecimal("7"));
        stubRecommendation(policy(3, null), List.of(larger, smaller), 1);
        smaller.setRackLayoutJson(null);
        var result = recommendationService().recommend(customer, recommendationRequest(GoodsCategory.FURNITURE));
        assertThat(result.recommendedUnitTypeId()).isEqualTo(larger.getId());
        assertThat(result.checksByUnitType().get(smaller.getId()).getResult()).isEqualTo(CompatibilityResult.INCOMPATIBLE);
        assertThat(result.checksByUnitType().get(smaller.getId()).isPalletLayoutMissing()).isTrue();
        assertThat(result.checksByUnitType().get(larger.getId()).getPalletPlan().requiredPallets()).isEqualTo(1);
    }

    @Test
    void comparesBothTypesUsingTheSelectedPeriodEvenWhenTheSmallerTypeDoesNotFit() {
        var smaller = unitType("SMALL", new BigDecimal("6"));
        smaller.setMaxLoadKg(new BigDecimal("80"));
        smaller.setMonthlyPrice(new BigDecimal("5500000"));
        var larger = unitType("MEDIUM", new BigDecimal("7"));
        larger.setMaxLoadKg(new BigDecimal("1200"));
        larger.setMonthlyPrice(new BigDecimal("9500000"));
        var packagePolicy = policy(6, null);
        packagePolicy.setDiscountRate(new BigDecimal("0.05"));
        stubRecommendation(packagePolicy, List.of(smaller, larger), 1);

        var request = recommendationRequest(GoodsCategory.FURNITURE);
        request.setEndDate(request.getStartDate().plusMonths(6));
        var item = request.getGoodsItems().getFirst();
        item.setLengthCm(new BigDecimal("40"));
        item.setWidthCm(new BigDecimal("30"));
        item.setHeightCm(new BigDecimal("30"));
        item.setQuantity(20);
        item.setWeightPerItemKg(new BigDecimal("5"));
        item.setMaxStackLayers(3);

        var result = recommendationService().recommend(customer, request);
        assertThat(result.recommendedUnitTypeId()).isEqualTo(larger.getId());
        assertThat(result.checksByUnitType().get(smaller.getId()).getResult()).isEqualTo(CompatibilityResult.INCOMPATIBLE);
        var smallPrice = result.pricingByUnitType().get(smaller.getId());
        var mediumPrice = result.pricingByUnitType().get(larger.getId());
        assertThat(smallPrice.rentAfterPackageDiscount()).isEqualByComparingTo("31350000");
        assertThat(mediumPrice.rentAfterPackageDiscount()).isEqualByComparingTo("54150000");
        assertThat(mediumPrice.securityDepositAmount().subtract(smallPrice.securityDepositAmount())).isEqualByComparingTo("4000000");
        assertThat(mediumPrice.reservationDepositAmount().subtract(smallPrice.reservationDepositAmount())).isEqualByComparingTo("5360000");
    }

    @Test
    void recommendsAnAvailableTypeWithAPalletThatFitsA133CmWideItem() {
        var smaller = unitType("SMALL", new BigDecimal("6"));
        var larger = unitType("LARGE", new BigDecimal("7"));
        stubRecommendation(policy(3, null), List.of(smaller, larger), 1);
        var request = recommendationRequest(GoodsCategory.FURNITURE);
        var item = request.getGoodsItems().getFirst();
        item.setLengthCm(new BigDecimal("12"));
        item.setWidthCm(new BigDecimal("133"));
        item.setHeightCm(new BigDecimal("12"));
        item.setQuantity(12);
        item.setWeightPerItemKg(new BigDecimal("12"));

        var service = recommendationService();
        var result = service.recommend(customer, request);
        assertThat(result.recommendedUnitTypeId()).isNull();
        assertThat(result.noAlternativeReason()).contains("vừa toàn bộ hàng", "còn gian trống");
        assertThat(result.checksByUnitType().values()).allSatisfy(check -> {
            assertThat(check.getIssues()).anySatisfy(issue -> assertThat(issue)
                .contains("chiều rộng 133 cm", "cạnh dài nhất", "13 cm")
                .doesNotContain("vượt chiều cao", "vượt tải"));
        });

        var standard = RackLayoutCalculatorTests.config(RackLayoutCalculatorTests.rack("R01", 0, 3, 2, 100, 1200));
        var widePallet = new RackLayout.Pallet(140, 80, new BigDecimal("16"), new BigDecimal("28"));
        var wideLayout = new RackLayout.Configuration(standard.clearances(), standard.racks(), widePallet);
        when(layouts.layoutForPacking(larger, larger.getRackLayoutJson()))
            .thenReturn(RackLayoutCalculator.calculate(larger, wideLayout));

        var recommendation = service.recommend(customer, request);
        assertThat(recommendation.recommendedUnitTypeId()).isEqualTo(larger.getId());
        assertThat(recommendation.noAlternativeReason()).isNull();
        var plan = recommendation.checksByUnitType().get(larger.getId()).getPalletPlan();
        assertThat(plan.palletLengthCm()).isEqualTo(140);
        assertThat(plan.palletWidthCm()).isEqualTo(80);
        assertThat(plan.requiredPallets()).isEqualTo(4);
        assertThat(plan.totalLoadedWeightKg()).isEqualByComparingTo("256");
        assertThat(plan.racks().getFirst().maxCargoHeightCm()).isEqualByComparingTo("99");

        when(capacityService.availableCount(any(), eq(larger.getId()), any(), any())).thenReturn(0L);
        var soldOut = service.recommend(customer, request);
        assertThat(soldOut.recommendedUnitTypeId()).isNull();
        assertThat(soldOut.checksByUnitType().get(larger.getId()).getIssues()).containsExactly(
            "Không còn gian kho trống trong kỳ thuê đã chọn.");
    }

    @Test
    void checksBookingAndRecommendsAnAvailableDemoTypeUsingTheSameSinglePalletLayout() {
        var small = unitType("SMALL", new BigDecimal("8"));
        small.setWidthM(new BigDecimal("10"));
        small.setMaxLoadKg(new BigDecimal("600"));
        var medium = unitType("MEDIUM", new BigDecimal("12"));
        medium.setWidthM(new BigDecimal("10.4"));
        medium.setMaxLoadKg(new BigDecimal("1200"));
        stubRecommendation(policy(3, null), List.of(small, medium), 1);
        when(layouts.layoutForPacking(small, small.getRackLayoutJson())).thenReturn(RackLayoutCalculator.calculate(small,
            RackLayoutCalculatorTests.singlePalletDemo(
                RackLayoutCalculatorTests.rack("R01", 0, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R02", 400, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R03", 0, 380, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R04", 400, 380, 3, 1, 100, 1200))));
        when(layouts.layoutForPacking(medium, medium.getRackLayoutJson())).thenReturn(RackLayoutCalculator.calculate(medium,
            RackLayoutCalculatorTests.singlePalletDemo(
                RackLayoutCalculatorTests.rack("R01", 0, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R02", 400, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R03", 800, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R04", 0, 420, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R05", 400, 420, 3, 1, 100, 1200),
                RackLayoutCalculatorTests.rack("R06", 800, 420, 3, 1, 100, 1200))));

        var service = recommendationService();
        var request = recommendationRequest(GoodsCategory.FURNITURE);
        var item = request.getGoodsItems().getFirst();
        item.setLengthCm(new BigDecimal("12"));
        item.setWidthCm(new BigDecimal("133"));
        item.setHeightCm(new BigDecimal("12"));
        item.setWeightPerItemKg(new BigDecimal("12"));
        item.setQuantity(12);
        item.setFragile(true);

        var fitsSmall = service.recommend(customer, request);
        assertThat(fitsSmall.recommendedUnitTypeId()).isEqualTo(small.getId());
        var smallPlan = fitsSmall.checksByUnitType().get(small.getId()).getPalletPlan();
        assertThat(smallPlan.requiredPallets()).isEqualTo(2);
        assertThat(smallPlan.availableSlots()).isEqualTo(24);

        item.setQuantity(51);
        var fitsMedium = service.recommend(customer, request);
        assertThat(fitsMedium.recommendedUnitTypeId()).isEqualTo(medium.getId());
        assertThat(fitsMedium.checksByUnitType().get(small.getId()).getIssues()).singleElement()
            .asString().contains("vượt 12 kg");
        var suggestedPlan = fitsMedium.checksByUnitType().get(medium.getId()).getPalletPlan();
        assertThat(suggestedPlan.palletLengthCm()).isEqualTo(170);
        assertThat(suggestedPlan.palletWidthCm()).isEqualTo(164);
        assertThat(suggestedPlan.requiredPallets()).isEqualTo(9);
        assertThat(suggestedPlan.availableSlots()).isEqualTo(36);
        var bookingRequest = new CompatibilityCheckRequest(
            facility.getId(), medium.getId(), request.getStartDate(), request.getEndDate(),
            request.getGoodsCondition(), request.getGoodsItems());
        assertThat(service.check(customer, bookingRequest).getPalletPlan()).isEqualTo(suggestedPlan);

        item.setWidthCm(new BigDecimal("171"));
        assertThat(service.recommend(customer, request).recommendedUnitTypeId()).isNull();
    }

    @Test
    void bookingUsesTheSharedLayoutAndSkipsUnitsHeldByAnotherCustomer() {
        stubAvailableUnitType(1);
        UUID otherHold = UUID.randomUUID();
        UUID ownChoice = UUID.randomUUID();
        when(units.findAvailableUnitIds(unitType.getId())).thenReturn(List.of(otherHold, ownChoice));
        when(capacityService.livePalletHoldUnitIds(
            eq(unitType.getId()), any(LocalDate.class), any(LocalDate.class)
        )).thenReturn(Set.of(otherHold));
        var available = new StorageUnit();
        available.setUnitType(unitType);
        when(units.findByIdForUpdate(ownChoice)).thenReturn(Optional.of(available));
        var result = service.checkQuotedSnapshot(customer, request(GoodsCategory.FURNITURE, null, null));
        assertThat(result.getPalletHeldUnit()).isSameAs(available);
        assertThat(result.getPalletPlan().requiredPallets()).isEqualTo(2);
        org.mockito.Mockito.verify(units, org.mockito.Mockito.never()).findByIdForUpdate(otherHold);
    }

    @Test
    void recommendationRejectsPolicyThatDoesNotCoverTheWholeRentalPeriod() {
        var policy = policy(3, LocalDate.now().plusDays(2));
        var recommendation = recommendationService();
        when(facilityRepository.findById(facility.getId())).thenReturn(Optional.of(facility));
        when(policyRepository.findByFacility_IdAndCode(facility.getId(), "P-3M")).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> recommendation.recommend(customer, recommendationRequest(GoodsCategory.FURNITURE)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("not effective");
    }

    private ReservationCompatibilityService recommendationService() {
        return new ReservationCompatibilityService(
            facilityRepository, unitTypeRepository, policyRepository, capacityService, goodsCatalogService, palletInventory
        );
    }

    private void stubRecommendation(RentalPackagePolicy policy, List<UnitType> candidates, long availableCount) {
        when(facilityRepository.findById(facility.getId())).thenReturn(Optional.of(facility));
        when(policyRepository.findByFacility_IdAndCode(facility.getId(), "P-3M")).thenReturn(Optional.of(policy));
        when(unitTypeRepository.findByFacility_IdOrderByCodeAsc(facility.getId())).thenReturn(candidates);
        when(unitTypeRepository.findById(any())).thenAnswer(invocation -> candidates.stream()
            .filter(candidate -> candidate.getId().equals(invocation.getArgument(0)))
            .findFirst());
        when(capacityService.availableCount(any(), any(), any(), any())).thenReturn(availableCount);
        if (availableCount > 0) {
            for (var candidate : candidates) stubPalletLayout(candidate, availableCount);
        }
    }

    private RentalPackagePolicy policy(int months, LocalDate effectiveTo) {
        var policy = new RentalPackagePolicy();
        policy.setFacility(facility);
        policy.setCode("P-3M");
        policy.setRentalMonths(months);
        policy.setPolicyVersion("policy-v1");
        policy.setEffectiveFrom(LocalDate.now().minusDays(1));
        policy.setEffectiveTo(effectiveTo);
        policy.setActive(true);
        return policy;
    }

    private UnitType unitType(String code, BigDecimal length) {
        var candidate = new UnitType();
        ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
        candidate.setFacility(facility);
        candidate.setCode(code);
        candidate.setStatus(UnitTypeStatus.active);
        candidate.setLengthM(length);
        candidate.setWidthM(new BigDecimal("2"));
        candidate.setHeightM(new BigDecimal("5"));
        candidate.setMaxLoadKg(new BigDecimal("1000"));
        candidate.setRackCount(4);
        candidate.setRackLengthM(new BigDecimal("2"));
        candidate.setRackWidthM(new BigDecimal("4"));
        candidate.setRackHeightM(new BigDecimal("4.5"));
        return candidate;
    }

    private ReservationRecommendationRequest recommendationRequest(GoodsCategory category) {
        var item = new GoodsItemRequest(
            category,
            category == GoodsCategory.OTHER ? "Other goods" : null,
            category == GoodsCategory.OTHER ? null : "Wood",
            category == GoodsCategory.OTHER ? "Composite" : null,
            "Packed item", null, 1,
            new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("50"),
            new BigDecimal("10"), false
        );
        var request = new ReservationRecommendationRequest();
        request.setFacilityId(facility.getId());
        request.setStartDate(LocalDate.now().plusDays(1));
        request.setEndDate(LocalDate.now().plusMonths(3).plusDays(1));
        request.setPricingPackageCode("P-3M");
        request.setGoodsCondition("Packed");
        request.setGoodsItems(List.of(item));
        return request;
    }

    private void stubAvailableUnitType(long availableCount) {
        when(facilityRepository.findById(facility.getId())).thenReturn(Optional.of(facility));
        when(unitTypeRepository.findById(unitType.getId())).thenReturn(Optional.of(unitType));
        when(capacityService.availableCount(
            facility.getId(), unitType.getId(),
            LocalDate.now().plusDays(1), LocalDate.now().plusMonths(3)
        )).thenReturn(availableCount);
        stubPalletLayout(unitType, availableCount);
    }

    private void stubPalletLayout(UnitType type, long count) {
        String snapshot = type.getId().toString();
        type.setRackLayoutJson(snapshot);
        var candidates = java.util.stream.LongStream.range(0, count).mapToObj(index -> UUID.randomUUID()).toList();
        lenient().when(units.findAvailableUnitIds(type.getId())).thenReturn(candidates);
        if (type.getLengthM().compareTo(BigDecimal.valueOf(4)) >= 0) {
            var configuration = RackLayoutCalculatorTests.config(RackLayoutCalculatorTests.rack("R01", 0, 3, 2, 100, 1200));
            lenient().when(layouts.layoutForPacking(type, snapshot)).thenReturn(RackLayoutCalculator.calculate(type, configuration));
        }
    }

    private CompatibilityCheckRequest request(
        GoodsCategory category,
        String customGoodsName,
        String customMaterial
    ) {
        GoodsItemRequest item = new GoodsItemRequest(
            category, customGoodsName, "Wood", customMaterial, "Desk", null, 2,
            new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("15"),
            new BigDecimal("18"), false
        );
        return new CompatibilityCheckRequest(
            facility.getId(), unitType.getId(), LocalDate.now().plusDays(1),
            LocalDate.now().plusMonths(3), "Packed", List.of(item)
        );
    }

    private GoodsItemRequest goodsItem(
        GoodsCategory category,
        String description,
        int quantity,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm
    ) {
        return new GoodsItemRequest(
            category, null, "Mixed", null, description, null, quantity,
            lengthCm, widthCm, heightCm, new BigDecimal("10"), false
        );
    }
}
