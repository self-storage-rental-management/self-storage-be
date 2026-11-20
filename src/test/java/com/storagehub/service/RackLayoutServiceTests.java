package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storagehub.api.facility.RackLayout;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RackLayoutServiceTests {
    private final StorageUnitRepository units = mock(StorageUnitRepository.class);
    private final UnitTypeRepository types = mock(UnitTypeRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final RackLayoutService service = new RackLayoutService(units, new FacilityScopeService(),
        mock(AdminAuthorizationService.class), audit, new ObjectMapper().findAndRegisterModules(),
        types, mock(ReservationCapacityService.class));
    private final UUID typeId = UUID.randomUUID();
    private final UUID facilityId = UUID.randomUUID();
    private final UnitType type = RackLayoutCalculatorTests.unit(600);
    private final ActorPrincipal manager = actor(RoleCode.MANAGER);
    private final RackLayout.Configuration configuration = RackLayoutCalculatorTests.config(
        RackLayoutCalculatorTests.rack("R01", 0, 3, 2, 100, 1200));

    RackLayoutServiceTests() {
        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        ReflectionTestUtils.setField(type, "id", typeId);
        facility.setStatus(FacilityStatus.active);
        type.setFacility(facility);
        when(types.findById(typeId)).thenReturn(Optional.of(type));
        when(types.findByIdForUpdate(typeId)).thenReturn(Optional.of(type));
    }

    @Test
    void savesConfirmedPalletDimensionsAndReadsOldLayoutsWithTheOriginalDefault() throws Exception {
        var pallet = new RackLayout.Pallet(140, 80, new BigDecimal("16"), new BigDecimal("28"));
        var updated = new RackLayout.Configuration(configuration.clearances(), configuration.racks(), pallet);
        var preview = service.preview(manager, typeId, updated);
        service.save(manager, typeId, new RackLayout.Save(updated, preview.previewKey()));
        assertThat(service.readForBusiness(actor(RoleCode.BUSINESS), typeId).result().configuration().pallet()).isEqualTo(pallet);
        assertThat(service.readForCustomer(actor(RoleCode.CUSTOMER), facilityId, typeId).smallBoxPlan().palletLengthCm())
            .isEqualTo(140);

        var mapper = new ObjectMapper().findAndRegisterModules();
        var oldSnapshot = (ObjectNode) mapper.readTree(type.getRackLayoutJson());
        ((ObjectNode) oldSnapshot.path("result").path("configuration")).remove("pallet");
        type.setRackLayoutJson(mapper.writeValueAsString(oldSnapshot));
        assertThat(service.readForCustomer(actor(RoleCode.CUSTOMER), facilityId, typeId).configuration().pallet())
            .isEqualTo(configuration.pallet());
    }

    @Test
    void upgradesOnlyTheUnusedOriginalDemoLayout() {
        var activeHolds = List.of(StorageUnitStatus.reserved, StorageUnitStatus.occupied,
            StorageUnitStatus.held, StorageUnitStatus.assigned);
        var newDemo = RackLayoutCalculatorTests.singlePalletDemo("R01", 0, 0);
        service.initializeDemoLayout(type, configuration);
        String oldSnapshot = type.getRackLayoutJson();

        when(units.countByFacility_IdAndUnitType_IdAndStatusIn(facilityId, typeId, activeHolds)).thenReturn(1L, 0L);
        service.upgradeDemoLayout(typeId, type.getVersion(), configuration, newDemo);
        assertThat(type.getRackLayoutJson()).isEqualTo(oldSnapshot);

        service.upgradeDemoLayout(typeId, type.getVersion(), configuration, newDemo);
        var customer = service.readForCustomer(actor(RoleCode.CUSTOMER), facilityId, typeId);
        assertThat(customer.configuration()).isEqualTo(newDemo);
        assertThat(customer.palletSlotCount()).isEqualTo(6);
        assertThat(type.getSmallBoxCapacity()).isEqualTo(90);
        assertThat(type.getLargeBoxCapacity()).isEqualTo(12);
        long revision = type.getPublishedRevision();

        service.upgradeDemoLayout(typeId, type.getVersion(), configuration, newDemo);
        assertThat(type.getPublishedRevision()).isEqualTo(revision);
        assertThat(type.getRackLayoutJson()).isNotEqualTo(oldSnapshot);
    }

    @Test
    void previewLeavesTypeUnchangedAndReadersUseTheSameConfirmedLayout() {
        var demoConfiguration = RackLayoutCalculatorTests.config(
            RackLayoutCalculatorTests.rack("R01", 0, 0, 3, 2, 100, 1200),
            RackLayoutCalculatorTests.rack("R02", 400, 0, 3, 2, 100, 1200),
            RackLayoutCalculatorTests.rack("R03", 0, 380, 3, 2, 100, 1200),
            RackLayoutCalculatorTests.rack("R04", 400, 380, 3, 2, 100, 1200));
        RackLayout.Preview preview = service.preview(manager, typeId, demoConfiguration);
        assertThat(type.getRackLayoutJson()).isNull();
        verifyNoInteractions(audit);

        RackLayout.Saved saved = service.save(manager, typeId, new RackLayout.Save(demoConfiguration, preview.previewKey()));

        assertThat(service.readForBusiness(actor(RoleCode.BUSINESS), typeId)).isEqualTo(saved);
        var customer = service.readForCustomer(actor(RoleCode.CUSTOMER), facilityId, typeId);
        assertThat(customer.withSamplePlans(null, null, null)).isEqualTo(saved.result());
        assertThat(customer.palletSlotCount()).isEqualTo(48);
        assertThat(customer.smallBoxesPerUnit()).isEqualTo(120);
        assertThat(customer.smallBoxPlan().requiredPallets()).isEqualTo(12);
        var small = customer.smallBoxPlan().items().getFirst().allocations().getFirst().capacity();
        assertThat(small.piecesPerLayer()).isEqualTo(8);
        assertThat(small.usableLayers()).isEqualTo(3);
        assertThat(small.maxPieces()).isEqualTo(10);
        assertThat(customer.largeBoxesPerUnit()).isEqualTo(40);
        assertThat(customer.largeBoxPlan().requiredPallets()).isEqualTo(40);
        var large = customer.largeBoxPlan().items().getFirst().allocations().getFirst().capacity();
        assertThat(large.piecesPerLayer()).isEqualTo(1);
        assertThat(large.usableLayers()).isEqualTo(1);
        assertThat(large.maxPieces()).isEqualTo(1);
        assertThat(customer.mixedBoxExample()).isNull();
    }

    @Test
    void changingSharedLayoutRequiresAnExactPreviewAndNoOccupiedUnit() {
        RackLayout.Preview preview = service.preview(manager, typeId, configuration);
        RackLayout.Configuration changed = RackLayoutCalculatorTests.config(
            RackLayoutCalculatorTests.rack("R02", 0, 3, 1, 100, 1200));

        assertThatThrownBy(() -> service.save(manager, typeId, new RackLayout.Save(changed, preview.previewKey())))
            .isInstanceOf(ApiException.class).hasMessageContaining("kiểm tra lại");
        when(units.countByFacility_IdAndUnitType_IdAndStatusIn(facilityId, typeId,
            List.of(StorageUnitStatus.reserved, StorageUnitStatus.occupied, StorageUnitStatus.held, StorageUnitStatus.assigned)))
            .thenReturn(1L);
        assertThatThrownBy(() -> service.save(manager, typeId, new RackLayout.Save(configuration, preview.previewKey())))
            .isInstanceOf(ApiException.class).hasMessageContaining("đang thuê");
        assertThat(type.getRackLayoutJson()).isNull();
        verifyNoInteractions(audit);
    }

    @Test
    void newTypeRequiresTheConfirmedDraftAndUsesCalculatedSummary() {
        var draft = new RackLayout.Draft(type.getLengthM(), type.getWidthM(), type.getHeightM(), type.getMaxLoadKg(), configuration);
        var preview = service.previewDraft(manager, facilityId, draft);
        assertThat(type.getRackLayoutJson()).isNull();
        assertThatThrownBy(() -> service.confirmNewType(manager, type, null))
            .isInstanceOf(ApiException.class).hasMessageContaining("xác nhận");
        type.setMaxLoadKg(type.getMaxLoadKg().add(java.math.BigDecimal.ONE));
        assertThatThrownBy(() -> service.confirmNewType(manager, type, new RackLayout.Save(configuration, preview.previewKey())))
            .isInstanceOf(ApiException.class).hasMessageContaining("kiểm tra lại");
        type.setMaxLoadKg(draft.maxLoadKg());
        when(units.findByFacility_Id(facilityId)).thenReturn(List.of());
        service.confirmNewType(manager, type, new RackLayout.Save(configuration, preview.previewKey()));
        assertThat(type.getRackCount()).isEqualTo(1);
        assertThat(type.getSmallBoxCapacity()).isEqualTo(preview.result().smallBoxesPerUnit());
        org.mockito.Mockito.verify(units).findByFacility_Id(facilityId);
    }

    @Test
    void newTypeCanConfirmTheConfigurationReturnedByAutomaticPreview() {
        type.setLengthM(new BigDecimal("12"));
        type.setWidthM(new BigDecimal("11.6"));
        type.setHeightM(new BigDecimal("5"));
        type.setMaxLoadKg(new BigDecimal("1000"));
        type.setRackLengthM(new BigDecimal("4"));
        type.setRackWidthM(new BigDecimal("2"));
        type.setRackHeightM(new BigDecimal("4.5"));
        type.setRackCount(4);
        type.setAisleWidthM(new BigDecimal("3"));
        type.setStorageAisleWidthM(new BigDecimal("2.5"));

        RackLayout.Draft draft = new RackLayout.Draft(
            type.getLengthM(), type.getWidthM(), type.getHeightM(), type.getMaxLoadKg(), null, 4);
        RackLayout.Preview preview = service.previewDraft(manager, facilityId, draft);

        // The UI submits the calculated configuration returned by the preview,
        // not the pre-calculation draft configuration.
        assertThat(preview.result().configuration().racks())
            .allSatisfy(rack -> assertThat(rack.maxLoadedPalletKg()).isEqualTo(new BigDecimal("100")));

        when(units.findByFacility_Id(facilityId)).thenReturn(List.of());
        service.confirmNewType(manager, type,
            new RackLayout.Save(preview.result().configuration(), preview.previewKey()));

        assertThat(type.getRackCount()).isEqualTo(4);
        assertThat(type.getRackLayoutJson()).isNotBlank();
        org.mockito.Mockito.verify(units).findByFacility_Id(facilityId);
    }

    @Test
    void autoConfigurationBuildsTwoBalancedRowsFromOnlyRackCount() {
        type.setRackCount(4);
        type.setRackLengthM(new BigDecimal("4"));
        type.setRackWidthM(new BigDecimal("2"));
        type.setRackHeightM(new BigDecimal("4.5"));
        type.setStorageAisleWidthM(new BigDecimal("2.5"));
        type.setAisleWidthM(new BigDecimal("3"));
        type.setLengthM(new BigDecimal("8"));
        // Four 4 m racks need two rows plus the configured 3 m main aisle;
        // keep this fixture physically large enough for the Backend check.
        type.setWidthM(new BigDecimal("12.2"));

        RackLayout.Configuration generated = service.autoConfiguration(type, 4);
        assertThat(generated.layoutMode()).isEqualTo("AUTO_BALANCED_TWO_ROWS");
        assertThat(generated.requestedRackCount()).isEqualTo(4);
        assertThat(generated.racks()).hasSize(4);
        assertThat(generated.racks()).extracting(RackLayout.Rack::orientationDeg)
            .containsOnly(new BigDecimal("90"));
        assertThat(generated.racks().subList(0, 2)).extracting(RackLayout.Rack::yCm)
            .containsOnly(new BigDecimal("30"));
        assertThat(generated.racks().subList(2, 4)).extracting(RackLayout.Rack::yCm)
            .containsOnly(new BigDecimal("730"));

        RackLayout.Result result = RackLayoutCalculator.calculate(type, generated);
        assertThat(result.rackCount()).isEqualTo(4);
        assertThat(result.totalPallets()).isEqualTo(24);
    }

    @Test
    void legacyBuiltInLayoutUsesTheUnitTypeAislesWhenItIsRebuilt() {
        type.setCode("XL");
        type.setRackCount(10);
        type.setStorageAisleWidthM(new BigDecimal("3.1"));
        type.setAisleWidthM(new BigDecimal("3.6"));

        RackLayout.Rack[] legacyRacks = java.util.stream.IntStream.range(0, 10)
            .mapToObj(index -> RackLayoutCalculatorTests.rack("R%02d".formatted(index + 1), 0, 3, 1, 100, 1200))
            .toArray(RackLayout.Rack[]::new);
        RackLayout.Configuration legacy = RackLayoutCalculatorTests.config(legacyRacks);

        RackLayout.Configuration normalized = ReflectionTestUtils.invokeMethod(
            service, "normalizeLegacyDemoConfiguration", type, "8.0", legacy);

        assertThat(normalized.racks().get(1).xCm()).isEqualByComparingTo("785");
        assertThat(normalized.racks().get(1).xCm()
            .subtract(normalized.racks().get(0).xCm())
            .subtract(normalized.racks().get(0).widthCm()))
            .isEqualByComparingTo("310");
        assertThat(normalized.racks().get(5).yCm()).isEqualByComparingTo("790");

        // Some records were already stamped with the current rules version
        // before the fixed spacing was deployed. They must be repaired too.
        RackLayout.Configuration currentVersionWithOldGap = new RackLayout.Configuration(
            legacy.clearances(), legacy.racks(), legacy.pallet(), true, legacy.palletRatings(),
            legacy.requestedRackCount(), "AUTO_BALANCED_TWO_ROWS");
        RackLayout.Configuration repairedCurrent = ReflectionTestUtils.invokeMethod(
            service, "normalizeLegacyDemoConfiguration", type, StorageHubRules.RULES_VERSION,
            currentVersionWithOldGap);

        assertThat(repairedCurrent.racks().get(1).xCm()).isEqualByComparingTo("785");
        assertThat(repairedCurrent.racks().get(1).xCm()
            .subtract(repairedCurrent.racks().get(0).xCm())
            .subtract(repairedCurrent.racks().get(0).widthCm()))
            .isEqualByComparingTo("310");
    }

    private ActorPrincipal actor(RoleCode role) {
        return new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(role), Set.of(),
            Map.of(facilityId, FacilityScopeLevel.MANAGE));
    }
}
