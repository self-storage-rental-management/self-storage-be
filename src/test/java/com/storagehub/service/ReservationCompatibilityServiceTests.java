package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
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
