package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.ReservationRecommendationRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
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

    private ReservationCompatibilityService service;
    private Facility facility;
    private UnitType unitType;
    private ActorPrincipal customer;

    @BeforeEach
    void setUp() {
        service = new ReservationCompatibilityService(
            facilityRepository, unitTypeRepository, capacityService
        );
        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        facility.setStatus(FacilityStatus.active);

        unitType = new UnitType();
        ReflectionTestUtils.setField(unitType, "id", UUID.randomUUID());
        unitType.setFacility(facility);
        unitType.setStatus(UnitTypeStatus.active);
        unitType.setLengthM(new BigDecimal("3"));
        unitType.setWidthM(new BigDecimal("2"));
        unitType.setHeightM(new BigDecimal("2.5"));
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
        assertThat(response.getRackUtilizationRate()).isEqualByComparingTo("0.80");
        assertThat(response.getUsableVolumePerRackM3()).isEqualByComparingTo("28.800000");
        assertThat(response.getRequiredRackCount()).isEqualTo(1);
        assertThat(response.getUnitRackCount()).isEqualTo(4);
        assertThat(response.getAvailableUnitCount()).isEqualTo(2);
        assertThat(response.getIssues()).isEmpty();
    }

    @Test
    void requiresStaffReviewForValidOtherGoods() {
        stubAvailableUnitType(1);
        CompatibilityCheckRequest request = request(GoodsCategory.OTHER, "Custom item", "Mixed material");

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
            .hasMessageContaining("customGoodsName and customMaterial");
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
        assertThat(response.getRequiredRackCount()).isEqualTo(1);
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
        var larger = unitType("LARGE", new BigDecimal("4"));
        var smaller = unitType("SMALL", new BigDecimal("3"));
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
        stubRecommendation(policy, List.of(unitType("OTHER", new BigDecimal("3"))), 1);

        var response = recommendation.recommend(customer, recommendationRequest(GoodsCategory.OTHER));

        assertThat(response.result()).isEqualTo(CompatibilityResult.REVIEW_REQUIRED);
        assertThat(response.recommendedUnitTypeId()).isNotNull();
    }

    @Test
    void recommendationReportsNoCapacityAndNoCompatibleUnit() {
        var policy = policy(3, null);
        var recommendation = recommendationService();
        stubRecommendation(policy, List.of(unitType("FULL", new BigDecimal("3"))), 0);

        var response = recommendation.recommend(customer, recommendationRequest(GoodsCategory.FURNITURE));

        assertThat(response.recommendedUnitTypeId()).isNull();
        assertThat(response.result()).isEqualTo(CompatibilityResult.INCOMPATIBLE);
        assertThat(response.issues()).anyMatch(issue -> issue.contains("physical unit"));
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
            facilityRepository, unitTypeRepository, policyRepository, capacityService, goodsCatalogService
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
        candidate.setHeightM(new BigDecimal("2.5"));
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
