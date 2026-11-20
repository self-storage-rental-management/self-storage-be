package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagehub.api.facility.RackLayout;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.PalletPlan;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.UnitType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RackLayoutCalculatorTests {
    @Test
    void previewsEachLevelAndLimitsSampleBoxesBySlotAndUnitWeight() {
        RackLayout.Result result = RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 3, 2, 100, 1200)));
        RackLayout.RackPreview rack = result.racks().getFirst();
        assertThat(rack.bayLengthCm()).isEqualByComparingTo("188");
        assertThat(rack.bayWidthCm()).isEqualByComparingTo("184");
        assertThat(rack.maxCargoHeightCm()).isEqualByComparingTo("100.6");
        assertThat(rack.levels()).extracting(level -> level.supportTopCm().intValueExact()).containsExactly(30, 175, 320);
        assertThat(rack.compartmentCount()).isEqualTo(6);
        assertThat(result.palletSlotCount()).isEqualTo(12);
        assertThat(rack.smallBoxesPerPallet()).isEqualTo(15);
        assertThat(rack.smallBoxesPerRack()).isEqualTo(60);
        assertThat(result.smallBoxesPerUnit()).isEqualTo(60);
        assertThat(result.largeBoxesPerUnit()).isEqualTo(12);
    }

    @Test
    void acceptsOnePalletPerCompartment() {
        RackLayout.Result result = RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 3, 1, 100, 1200)));
        assertThat(result.palletSlotCount()).isEqualTo(6);
    }

    @Test
    void usesTheManagerSelectedLevelCountWhenCalculatingCompartments() {
        RackLayout.Rack rack = new RackLayout.Rack(
            "R01", value(400), value(200), value(450), value(30), value(30), BigDecimal.ZERO,
            value(4), value(8), value(2), value(15), value(15), value(1),
            value(100), value(1200), value(200), value(100));
        RackLayout.Result result = RackLayoutCalculator.calculate(unit(600), new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)), List.of(rack), null, true,
            List.of()));

        assertThat(result.racks().getFirst().levels()).hasSize(2);
        assertThat(result.racks().getFirst().bayCount()).isEqualTo(3);
        assertThat(result.racks().getFirst().compartmentCount()).isEqualTo(6);
        assertThat(result.totalCompartments()).isEqualTo(6);
    }

    @Test
    void derivesTheAllowedLevelCountFromRackHeightInsteadOfAThreeLevelCap() {
        UnitType unit = unit(1200);
        unit.setLengthM(value(12));
        unit.setWidthM(new BigDecimal("11.6"));
        unit.setHeightM(value(7));
        RackLayout.Rack rack = new RackLayout.Rack(
            "R01", value(400), value(200), value(600), value(30), value(30), BigDecimal.ZERO,
            value(3), value(8), value(4), value(15), value(15), value(1),
            value(100), value(1200), value(200), value(100));

        RackLayout.Result result = RackLayoutCalculator.calculate(unit, new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)), List.of(rack), null, true,
            List.of()));

        assertThat(RackLayoutCalculator.maxLevelCount(rack, result.configuration().pallet(),
            result.configuration().clearances())).isEqualTo(4);
        assertThat(result.racks().getFirst().levels()).hasSize(4);
        assertThat(result.maxCargoHeightCm()).isEqualByComparingTo("98.25");
    }

    @Test
    void calculatesV6PalletOnBothBeamsAndUsesEffectiveRackLoad() {
        UnitType unit = unit(600);
        unit.setLengthM(value(12));
        unit.setWidthM(new BigDecimal("11.6"));
        var configuration = v6Configuration(new RackLayout.Pallet(210, 170, value(18), value(40), value(500)));

        var result = RackLayoutCalculator.calculate(unit, configuration);
        var preview = result.racks().getFirst();

        assertThat(preview.bayLengthCm()).isEqualByComparingTo("188");
        assertThat(preview.bayWidthCm()).isEqualByComparingTo("184");
        assertThat(preview.maxCargoHeightCm()).isEqualByComparingTo("97");
        assertThat(preview.palletAlongRackCm()).isEqualTo(170);
        assertThat(preview.palletAcrossRackCm()).isEqualTo(210);
        assertThat(preview.smallBoxesPerPallet()).isEqualTo(12);
        assertThat(preview.largeBoxesPerPallet()).isEqualTo(4);
        assertThat(preview.effectiveRackLoadKg()).isEqualByComparingTo("600");
        assertThat(result.smallBoxesPerUnit()).isEqualTo(72);
        assertThat(result.largeBoxesPerUnit()).isEqualTo(24);
        assertThat(result.totalCompartments()).isEqualTo(6);
        assertThat(result.totalPallets()).isEqualTo(6);
        assertThat(result.maxCargoLengthCm()).isEqualByComparingTo("210");
        assertThat(result.maxCargoWidthCm()).isEqualByComparingTo("170");
        assertThat(result.maxCargoHeightCm()).isEqualByComparingTo("97");
        assertThat(result.maxCargoWeightPerCompartmentKg()).isEqualByComparingTo("60");
        assertThat(result.maxCargoWeightPerRackKg()).isEqualByComparingTo("360");
        // This fixture contains one rack, so its physical cargo limit is
        // 600 kg total load minus 6 empty pallets × 40 kg = 360 kg cargo.
        // A four-rack warehouse reaches the unit quota of 600 kg.
        assertThat(result.maxCargoWeightPerWarehouseKg()).isEqualByComparingTo("360");
    }

    @Test
    void enforcesPalletLengthAndWidthBoundsInDirectBeamMode() {
        UnitType unit = unit(600);
        unit.setLengthM(value(12));
        unit.setWidthM(new BigDecimal("11.6"));
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit,
            v6Configuration(new RackLayout.Pallet(199, 170, value(18), value(40), value(500)))))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getDetails().toString()).contains("nhỏ hơn chiều rộng ngoài"));
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit,
            v6Configuration(new RackLayout.Pallet(211, 170, value(18), value(40), value(500)))))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getDetails().toString()).contains("vượt giới hạn 210 cm"));
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit,
            v6Configuration(new RackLayout.Pallet(210, 171, value(18), value(40), value(500)))))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getDetails().toString()).contains("chiều rộng tấm kê 171 cm"));
    }

    @Test
    void appliesTheWarehouseQuotaAfterSummingCargoCapacityOfAllV6Racks() {
        UnitType unit = unit(600);
        unit.setLengthM(value(12));
        unit.setWidthM(new BigDecimal("11.6"));
        RackLayout.Pallet pallet = new RackLayout.Pallet(210, 170, value(18), value(40), value(500));
        var result = RackLayoutCalculator.calculate(unit, v6Configuration(pallet,
            v6Rack("R01", 30, 30), v6Rack("R02", 440, 30),
            v6Rack("R03", 30, 380), v6Rack("R04", 440, 380)));

        assertThat(result.maxCargoWeightPerRackKg()).isEqualByComparingTo("360");
        assertThat(result.maxCargoWeightPerWarehouseKg()).isEqualByComparingTo("600");
        assertThat(result.smallBoxesPerUnit()).isEqualTo(120);
        assertThat(result.largeBoxesPerUnit()).isEqualTo(40);
    }

    @Test
    void appliesRackWeightLimitIncludingTarePerLoadedPallet() {
        RackLayout.Result result = RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 3, 2, 100, 400)));
        assertThat(result.smallBoxesPerUnit()).isEqualTo(20);
        assertThat(result.largeBoxesPerUnit()).isEqualTo(6);
    }

    @Test
    void reportsLowLevelsAndOversizedPalletRequestTogether() {
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 4, 3, 100, 1200))))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getDetails().toString()).contains("64.35 cm", "3 pallet", "188 cm");
            });
    }

    @Test
    void rejectsOverlappingRackFootprints() {
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit(600),
            config(rack("R01", 0, 3, 2, 100, 1200), rack("R02", 100, 3, 2, 100, 1200))))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getDetails().toString()).contains("R02: chồng vị trí với khung R01");
            });
    }

    @Test
    void usesTheSamePalletCountAsTheSampleAndKeepsFragileGoodsOnOneLayer() {
        var layout = RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 3, 2, 100, 1200)));
        var item = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Wood", null, "Box", null,
            15, value(40), value(30), value(30), value(5), false);
        item.setMaxStackLayers(3);

        var packed = PalletPacking.fit(layout, List.of(item)).plan();
        assertThat(packed.requiredPallets()).isEqualTo(2);
        assertThat(packed.items().getFirst().maxPiecesOnOnePallet()).isEqualTo(10);
        assertThat(packed.totalLoadedWeightKg()).isEqualByComparingTo("125");

        item.setFragile(true);
        var fragile = PalletPacking.fit(layout, List.of(item)).plan();
        assertThat(fragile.requiredPallets()).isEqualTo(3);
        assertThat(fragile.items().getFirst().maxPiecesOnOnePallet()).isEqualTo(8);
        assertThat(fragile.totalLoadedWeightKg()).isEqualByComparingTo("150");
    }

    @Test
    void splitsNineSmallFragileItemsAcrossThreePalletsBecauseTheBeamLoadIsShared() {
        var layout = RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 3, 2, 100, 1200)));
        var item = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Wood", null, "Box", null,
            9, value(12), value(12), value(12), value(12), true);

        var result = PalletPacking.fit(layout, List.of(item)).plan();
        assertThat(result.requiredPallets()).isEqualTo(3);
        assertThat(result.items().getFirst().maxPiecesOnOnePallet()).isEqualTo(4);
        assertThat(result.totalLoadedWeightKg()).isEqualByComparingTo("183");
        var placements = result.items().getFirst().allocations();
        assertThat(placements).extracting(allocation -> allocation.piecesPerPallet()).containsExactly(4, 4, 1);
        assertThat(placements).allSatisfy(allocation -> {
            assertThat(allocation.rackCode()).isEqualTo("R01");
            assertThat(allocation.palletCount()).isEqualTo(1);
            assertThat(allocation.capacity().maxPiecesByDimensions()).isEqualTo(60);
            assertThat(allocation.capacity().cargoWeightLimitKg()).isEqualByComparingTo("50");
            assertThat(allocation.capacity().maxPiecesByWeight()).isEqualTo(4);
        });
        assertThat(placements.getFirst().loadedWeightKg()).isEqualByComparingTo("73");
        assertThat(placements.getLast().loadedWeightKg()).isEqualByComparingTo("37");
    }

    @Test
    void reportsPalletCapacityIndependentlyOfTheNumberOfItemsOrdered() {
        var layout = RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 3, 2, 100, 1200)));
        var item = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Wood", null, "Box", null,
            3, value(12), value(12), value(12), value(12), true);
        var placement = PalletPacking.fit(layout, List.of(item)).plan().items().getFirst().allocations().getFirst();
        assertThat(placement.piecesPerPallet()).isEqualTo(3);
        assertThat(placement.capacity().maxPieces()).isEqualTo(4);
    }

    @Test
    void rejectsTheMixedExampleWithOneMoreSmallBoxBeforeLookingForSlots() {
        var layout = RackLayoutCalculator.calculate(unit(600), config(
            rack("R01", 0, 3, 2, 100, 1200), rack("R02", 400, 3, 2, 100, 1200),
            rack("R03", 0, 380, 3, 2, 100, 1200), rack("R04", 400, 380, 3, 2, 100, 1200)));
        var result = PalletPacking.planMixedSample(layout, 64, 19);
        assertThat(result.plan()).isNull();
        assertThat(result.issue()).isEqualTo("Đơn nặng 605 kg, vượt 5 kg so với giới hạn 600 kg hàng của gian.");
    }

    @Test
    void calculatesTheFourSinglePalletDemoCapacities() {
        int[] cargoWeights = {600, 1200, 2400, 3600};
        int[] expectedSmall = {120, 240, 480, 720};
        int[] expectedLarge = {40, 72, 96, 120};
        for (int i = 0; i < cargoWeights.length; i++) {
            var unit = unit(cargoWeights[i]);
            int racksPerRow = i + 2;
            unit.setLengthM(value(4 * racksPerRow));
            var racks = new RackLayout.Rack[racksPerRow * 2];
            for (int r = 0; r < racks.length; r++) {
                racks[r] = rack("R" + r, 400 * (r % racksPerRow), r < racksPerRow ? 0 : 380 + 40 * i, 3, 1, 100, 1200);
            }
            var result = RackLayoutCalculator.calculate(unit, singlePalletDemo(racks));
            assertThat(result.palletSlotCount()).isEqualTo(6 * racks.length);
            assertThat(result.smallBoxesPerUnit()).isEqualTo(expectedSmall[i]);
            assertThat(result.largeBoxesPerUnit()).isEqualTo(expectedLarge[i]);
            assertThat(result.racks()).allSatisfy(rack -> {
                assertThat(rack.palletAlongRackCm()).isEqualTo(170);
                assertThat(rack.palletAcrossRackCm()).isEqualTo(164);
                assertThat(rack.smallBoxesPerPallet()).isEqualTo(15);
                assertThat(rack.largeBoxesPerPallet()).isEqualTo(2);
            });
            assertThat(PalletPacking.planSample(result, result.smallBox(), expectedSmall[i]).requiredPallets())
                .isEqualTo(expectedSmall[i] / 15);
            assertThat(PalletPacking.planSample(result, result.largeBox(), expectedLarge[i]).requiredPallets())
                .isEqualTo(expectedLarge[i] / 2);
        }
    }

    @Test
    void explainsTheActualHeightOrPalletWeightThatPreventsPacking() {
        var layout = RackLayoutCalculator.calculate(unit(600), config(rack("R01", 0, 3, 2, 100, 1200)));
        var item = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Wood", null, "Box", null,
            1, value(40), value(30), value(120), value(76), false);
        var tooTall = PalletPacking.fit(layout, List.of(item));
        assertThat(tooTall.issue()).contains("chiều cao 120 cm", "100,6 cm");
        item.setHeightCm(value(30));
        var tooHeavy = PalletPacking.fit(layout, List.of(item));
        assertThat(tooHeavy.issue()).contains("nặng 76 kg", "50 kg hàng");
    }

    @Test
    void rotatesADeclaredHorizontalPackageThroughAllThreeDimensions() {
        UnitType unit = unit(600);
        unit.setLengthM(value(12));
        unit.setWidthM(new BigDecimal("11.6"));
        var layout = RackLayoutCalculator.calculate(unit,
            v6Configuration(new RackLayout.Pallet(210, 170, value(18), value(40), value(500))));
        var item = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Wood", null, "Tall package", null,
            1, value(12), value(12), value(123), value(12), false);
        item.setAllowHorizontalPlacement(true);

        var result = PalletPacking.fit(layout, List.of(item));

        assertThat(result.issue()).isNull();
        var capacity = result.plan().items().getFirst().allocations().getFirst().capacity();
        assertThat(capacity.placementLengthCm()).isEqualByComparingTo("12");
        assertThat(capacity.placementWidthCm()).isEqualByComparingTo("123");
        assertThat(capacity.placementHeightCm()).isEqualByComparingTo("12");
        assertThat(capacity.piecesPerLayer()).isEqualTo(17);
    }

    @Test
    void rotates123By180By19OnThePalletAndLimitsLayersByHeight() {
        UnitType unit = unit(600);
        unit.setLengthM(value(12));
        unit.setWidthM(new BigDecimal("11.6"));
        var layout = RackLayoutCalculator.calculate(unit,
            v6Configuration(new RackLayout.Pallet(210, 170, value(18), value(40), value(500))));
        var item = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Wood", null, "Box", null,
            1, value(123), value(180), value(19), value(12), false);
        item.setStackable(true);
        item.setAllowHorizontalPlacement(true);

        var result = PalletPacking.fit(layout, List.of(item));
        var capacity = result.plan().items().getFirst().allocations().getFirst().capacity();

        assertThat(capacity.placementLengthCm()).isEqualByComparingTo("180");
        assertThat(capacity.placementWidthCm()).isEqualByComparingTo("123");
        assertThat(capacity.placementHeightCm()).isEqualByComparingTo("19");
        assertThat(capacity.piecesPerLayer()).isEqualTo(1);
        assertThat(capacity.usableLayers()).isEqualTo(5);
        assertThat(capacity.maxPiecesByDimensions()).isEqualTo(5);
        assertThat(capacity.maxPiecesByWeight()).isEqualTo(5);
        assertThat(capacity.maxPieces()).isEqualTo(5);

        item.setStackable(false);
        var nonStackable = PalletPacking.fit(layout, List.of(item));
        assertThat(nonStackable.plan().items().getFirst().allocations().getFirst().capacity().usableLayers())
            .isEqualTo(1);
    }

    @Test
    void v8GeneratesThePalletFromRackGeometryAndRejectsThirdPallet() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        unit.setMaxStoredLoadKg(value(100));
        RackLayout.Pallet rating = new RackLayout.Pallet(210, 170, value("18"), value(40), value(500));
        RackLayout.Rack rack = v6Rack("R01", 30, 30);
        var v8 = new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)),
            List.of(rack), rating, true);

        var result = RackLayoutCalculator.calculate(unit, v8);

        assertThat(result.configuration().pallet().lengthCm()).isEqualTo(210);
        assertThat(result.configuration().pallet().widthCm()).isEqualTo(170);
        assertThat(result.configuration().pallet().heightCm()).isEqualByComparingTo("18");
        assertThat(result.configuration().pallet().emptyKg()).isEqualByComparingTo("32.13");
        assertThat(result.warehouseAreaM2()).isEqualByComparingTo("139.2");
        assertThat(result.maxLevelsByHeight()).isEqualTo(3);
        assertThat(result.totalPalletEmptyWeightKg()).isEqualByComparingTo("192.78");
        assertThat(result.maxStoredLoadAtCapacityKg()).isEqualByComparingTo("600");
        assertThat(result.maxPalletCountByLoad()).isEqualTo(3);
        assertThat(result.racks().getFirst().maxPalletCountByGeometry()).isEqualTo(2);
        assertThat(result.maxCargoHeightCm()).isEqualByComparingTo("97");
        assertThat(result.maxCargoWeightPerCompartmentKg()).isEqualByComparingTo("67.87");

        RackLayout.Rack threePallets = new RackLayout.Rack(
            rack.code(), rack.lengthCm(), rack.widthCm(), rack.heightCm(), rack.xCm(), rack.yCm(),
            rack.orientationDeg(), rack.uprightFrameCount(), rack.uprightSizeCm(), rack.levelCount(),
            rack.beamHeightCm(), rack.firstBeamBottomCm(), value(3), rack.maxLoadedPalletKg(),
            rack.maxRackLoadKg(), rack.beamOuterSpanCm(), rack.maxBeamPairLoadKg());
        var invalid = new RackLayout.Configuration(v8.clearances(), List.of(threePallets), rating, true);
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit, invalid))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Cấu hình kệ pallet chưa hợp lệ");
    }

    @Test
    void v8AllowsTwoPalletsWhenTheGeneratedAlongRackWidthIsAtLeastEighty() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        RackLayout.Rack rack = new RackLayout.Rack(
            "R01", value(400), value(200), value(450), value(30), value(30), BigDecimal.ZERO,
            value(3), value(8), value(3), value(15), value(15), value(2),
            value(100), value(1200), value(200), value(100));
        var configuration = new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)),
            List.of(rack), new RackLayout.Pallet(210, 170, value(18), value(40), value(500)), true,
            List.of(
                new RackLayout.PalletRating(210, 170, value(18), value(40), value(500)),
                new RackLayout.PalletRating(210, 80, value(18), value(40), value(500))));

        var result = RackLayoutCalculator.calculate(unit, configuration);

        assertThat(result.configuration().pallet().lengthCm()).isEqualTo(210);
        assertThat(result.configuration().pallet().widthCm()).isEqualTo(80);
        assertThat(result.configuration().pallet().emptyKg()).isEqualByComparingTo("15.12");
        assertThat(result.maxPalletCountByLoad()).isEqualTo(6);
        assertThat(result.racks().getFirst().maxPalletCountByGeometry()).isEqualTo(2);
        assertThat(result.maxCargoWeightPerCompartmentKg()).isEqualByComparingTo("69.76");
    }

    @Test
    void rejectsV8LevelLoadAndPalletGeometryInputsBeforeReturningAPreview() {
        UnitType unit = unit(500);
        RackLayout.Clearances clearances = new RackLayout.Clearances(
            value(10), value(9), value(0), value(15), value(5), value(30));
        RackLayout.Rack base = v6Rack("R01", 30, 30);

        RackLayout.Rack tooManyLevels = new RackLayout.Rack(
            base.code(), base.lengthCm(), base.widthCm(), base.heightCm(), base.xCm(), base.yCm(),
            base.orientationDeg(), base.uprightFrameCount(), base.uprightSizeCm(), value(4),
            base.beamHeightCm(), base.firstBeamBottomCm(), base.palletsPerCompartment(),
            base.maxLoadedPalletKg(), base.maxRackLoadKg(), base.beamOuterSpanCm(), base.maxBeamPairLoadKg());
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit,
            new RackLayout.Configuration(clearances, List.of(tooManyLevels), null, true)))
            .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getDetails().toString())
                .contains("chỉ sử dụng được tối đa 3 tầng"));

        RackLayout.Rack lowBeamLoad = new RackLayout.Rack(
            base.code(), base.lengthCm(), base.widthCm(), base.heightCm(), base.xCm(), base.yCm(),
            base.orientationDeg(), base.uprightFrameCount(), base.uprightSizeCm(), base.levelCount(),
            base.beamHeightCm(), base.firstBeamBottomCm(), base.palletsPerCompartment(),
            value(40), base.maxRackLoadKg(), base.beamOuterSpanCm(), value(30));
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit,
            new RackLayout.Configuration(clearances, List.of(lowBeamLoad), null, true)))
            .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getDetails().toString())
                .contains("giới hạn tải mỗi khoang phải lớn hơn tổng khối lượng 1 tấm kê rỗng (32,13 kg)"));

        RackLayout.Rack lowRackLoad = new RackLayout.Rack(
            base.code(), base.lengthCm(), base.widthCm(), base.heightCm(), base.xCm(), base.yCm(),
            base.orientationDeg(), base.uprightFrameCount(), base.uprightSizeCm(), base.levelCount(),
            base.beamHeightCm(), base.firstBeamBottomCm(), base.palletsPerCompartment(),
            base.maxLoadedPalletKg(), value(180), base.beamOuterSpanCm(), base.maxBeamPairLoadKg());
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit,
            new RackLayout.Configuration(clearances, List.of(lowRackLoad), null, true)))
            .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getDetails().toString())
                .contains("giới hạn tải mỗi khung phải lớn hơn tổng khối lượng 6 tấm kê rỗng (192,78 kg)"));

        RackLayout.Rack tooManyPallets = new RackLayout.Rack(
            base.code(), base.lengthCm(), base.widthCm(), base.heightCm(), base.xCm(), base.yCm(),
            base.orientationDeg(), base.uprightFrameCount(), base.uprightSizeCm(), base.levelCount(),
            base.beamHeightCm(), base.firstBeamBottomCm(), value(3), base.maxLoadedPalletKg(),
            base.maxRackLoadKg(), base.beamOuterSpanCm(), base.maxBeamPairLoadKg());
        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit,
            new RackLayout.Configuration(clearances, List.of(tooManyPallets), null, true)))
            .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getDetails().toString())
                .contains("số tấm kê mỗi khoang là 3, nhưng kích thước khoang hiện tại chỉ bố trí được tối đa 2 tấm kê mỗi khoang"));
    }

    @Test
    void calculatesMaximumFeasiblePalletCountFromGeometryAndMatchingRatings() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        RackLayout.Rack rack = v6Rack("R01", 30, 30);
        RackLayout.PalletRating one = new RackLayout.PalletRating(210, 170, value(18), value(40), value(500));
        RackLayout.PalletRating two = new RackLayout.PalletRating(210, 80, value(18), value(40), value(500));
        var configuration = new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)), List.of(rack), one.toPallet(), true,
            List.of(one, two));

        var result = RackLayoutCalculator.calculate(unit, configuration);

        assertThat(result.maxPalletCountByGeometry()).isEqualTo(2);
        assertThat(result.maxFeasiblePalletCount()).isEqualTo(2);
        assertThat(result.palletRatingOptions()).extracting(RackLayout.PalletRatingOption::feasible)
            .containsExactly(true, true);
    }

    @Test
    void usesTheFixedPalletSpecificationWhenNoManagerRatingIsProvided() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        RackLayout.Rack rack = v6Rack("R01", 30, 30);
        var configuration = new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)), List.of(rack), null, true,
            List.of());

        var result = RackLayoutCalculator.calculate(unit, configuration);

        assertThat(result.maxFeasiblePalletCount()).isEqualTo(2);
        assertThat(result.palletRatingOptions()).allSatisfy(option ->
            assertThat(option.ratingConfigured()).isTrue());
    }

    @Test
    void exposesTheV81VersionAndDoesNotUseTheLegacyPerPalletLoadField() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        RackLayout.Rack rack = new RackLayout.Rack(
            "R01", value(400), value(200), value(450), value(30), value(30), BigDecimal.ZERO,
            value(3), value(8), value(3), value(15), value(15), value(1),
            value(1), value(1200), value(200), value(100));

        RackLayout.Result result = RackLayoutCalculator.calculate(unit,
            new RackLayout.Configuration(new RackLayout.Clearances(value(0), value(0), value(0), value(0)),
                List.of(rack), null, true, List.of()));

        assertThat(result.rulesVersion()).isEqualTo(StorageHubRules.RULES_VERSION);
        assertThat(result.maxCargoWeightPerCompartmentKg()).isEqualByComparingTo("67.87");
        assertThat(result.operationStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void rejectsAnUnexpandedAutomaticRackCountInsteadOfSilentlyReducingIt() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("12.2"));
        RackLayout.Configuration configuration = new RackLayout.Configuration(
            new RackLayout.Clearances(value(10), value(9), value(0), value(15), value(5), value(30)),
            List.of(v6Rack("R01", 30, 30)), null, true, List.of(), 4, "AUTO_BALANCED_TWO_ROWS");

        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit, configuration))
            .isInstanceOf(ApiException.class)
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getDetails().toString()).contains("không tự giảm số khung"));
    }

    @Test
    void rejectsOnlyOneGateDimensionAndAllowsZeroOverheadClearance() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        unit.setGateWidthM(new BigDecimal("2.4"));
        RackLayout.Configuration config = new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)),
            List.of(v6Rack("R01", 30, 30)), null, true, List.of());

        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit, config))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getDetails().toString()).contains("đồng thời chiều rộng và chiều cao"));

        unit.setGateHeightM(new BigDecimal("3"));
        unit.setLowestObstructionHeightM(new BigDecimal("5"));
        unit.setOverheadClearanceM(BigDecimal.ZERO);
        RackLayout.Result result = RackLayoutCalculator.calculate(unit, config);
        assertThat(result.warnings()).noneMatch(warning -> warning.contains("vật cản/trần"));
    }

    @Test
    void rejectsObstructionAndOverheadClearanceThatCannotFitTheWarehouse() {
        UnitType unit = unit(600);
        unit.setHeightM(value(10));
        unit.setLowestObstructionHeightM(value(12));
        unit.setOverheadClearanceM(value(12));

        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit, config(rack("R01", 0, 3, 1, 100, 1200))))
            .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getDetails().toString())
                .contains("Cao độ vật cản thấp nhất không được cao hơn chiều cao gian kho")
                .contains("Khoảng hở an toàn phía trên kệ phải nhỏ hơn chiều cao sử dụng"));
    }

    @Test
    void rejectsRackHigherThanTheUsableWarehouseHeight() {
        UnitType unit = unit(600);
        unit.setHeightM(value(5));
        unit.setLowestObstructionHeightM(value(4));
        unit.setOverheadClearanceM(value(0));
        RackLayout.Rack rack = new RackLayout.Rack(
            "R01", value(400), value(200), value(450), value(30), value(30), BigDecimal.ZERO,
            value(3), value(8), value(3), value(15), value(15), value(1),
            value(100), value(1200), value(200), value(100));

        assertThatThrownBy(() -> RackLayoutCalculator.calculate(unit, config(rack)))
            .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getDetails().toString())
                .contains("chiều cao sử dụng của kho chỉ còn 400 cm")
                .contains("nhỏ hơn chiều cao khung"));
    }

    @Test
    void packsDifferentGoodsLinesOnOnePalletWithNonOverlappingThreeDimensionalPlacements() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        RackLayout.Rack rack = v6Rack("R01", 30, 30);
        RackLayout.Pallet pallet = new RackLayout.Pallet(210, 170, value(18), value(40), value(500));
        var configuration = new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)), List.of(rack), pallet, true,
            List.of(new RackLayout.PalletRating(210, 170, value(18), value(40), value(500))));
        var layout = RackLayoutCalculator.calculate(unit, configuration);
        var first = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Gỗ", null, "Hàng 1", null,
            1, value(100), value(80), value(40), value(5), false);
        var second = new GoodsItemRequest(GoodsCategory.DECOR, null, "Gỗ", null, "Hàng 2", null,
            1, value(100), value(80), value(40), value(5), false);

        var result = PalletPacking.fit(layout, List.of(first, second));

        assertThat(result.issue()).isNull();
        assertThat(result.plan().requiredPallets()).isEqualTo(1);
        assertThat(result.plan().pallets()).hasSize(1);
        assertThat(result.plan().pallets().getFirst().items()).extracting(PalletPlan.ItemAllocation::itemNumber)
            .containsExactlyInAnyOrder(1, 2);
        assertThat(result.plan().pallets().getFirst().placements()).hasSize(2);
        assertThat(result.plan().pallets().getFirst().placements()).allSatisfy(placement ->
            assertThat(placement.zCm()).isEqualByComparingTo("0"));
    }

    @Test
    void usesASecondThreeDimensionalLayerOnlyWhenStackingIsAllowed() {
        UnitType unit = unit(600);
        unit.setLengthM(new BigDecimal("12"));
        unit.setWidthM(new BigDecimal("11.6"));
        RackLayout.Rack rack = v6Rack("R01", 30, 30);
        RackLayout.Pallet pallet = new RackLayout.Pallet(210, 170, value(18), value(40), value(500));
        var configuration = new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(0), value(0), value(0)), List.of(rack), pallet, true,
            List.of(new RackLayout.PalletRating(210, 170, value(18), value(40), value(500))));
        var layout = RackLayoutCalculator.calculate(unit, configuration);
        var item = new GoodsItemRequest(GoodsCategory.FURNITURE, null, "Gỗ", null, "Hàng xếp chồng", null,
            5, value(100), value(80), value(40), value(5), false);
        item.setStackable(true);

        var result = PalletPacking.fit(layout, List.of(item));

        assertThat(result.issue()).isNull();
        assertThat(result.plan().requiredPallets()).isEqualTo(1);
        assertThat(result.plan().pallets().getFirst().placements()).extracting(PalletPlan.BoxPlacement::zCm)
            .anyMatch(z -> z.compareTo(value("40")) == 0);
    }

    static UnitType unit(int maxLoad) {
        UnitType unit = new UnitType();
        unit.setLengthM(value(8));
        unit.setWidthM(value(10));
        unit.setHeightM(value(5));
        unit.setMaxLoadKg(value(maxLoad));
        return unit;
    }

    static RackLayout.Configuration config(RackLayout.Rack... racks) {
        return new RackLayout.Configuration(new RackLayout.Clearances(value(10), value(9), value(10), value(15)), List.of(racks));
    }

    static RackLayout.Configuration singlePalletDemo(String code, int x, int y) {
        return singlePalletDemo(rack(code, x, y, 3, 1, 100, 1200));
    }

    static RackLayout.Configuration singlePalletDemo(RackLayout.Rack... racks) {
        return new RackLayout.Configuration(config(racks).clearances(), List.of(racks),
            new RackLayout.Pallet(170, 164, new BigDecimal("14.4"), value(25)));
    }

    static RackLayout.Configuration v6Configuration(RackLayout.Pallet pallet) {
        return v6Configuration(pallet, v6Rack("R01", 30, 30));
    }

    static RackLayout.Configuration v6Configuration(RackLayout.Pallet pallet, RackLayout.Rack... racks) {
        return new RackLayout.Configuration(
            new RackLayout.Clearances(value(0), value(9), value(0), value(15), value(5), value(30)),
            List.of(racks), pallet);
    }

    static RackLayout.Rack v6Rack(String code, int x, int y) {
        return new RackLayout.Rack(code, value(400), value(200), value(450), value(x), value(y),
            BigDecimal.ZERO, value(3), value(8), value(3), value(15), value(15), value(1),
            value(100), value(1200), value(200), value(100));
    }

    static RackLayout.Rack rack(String code, int x, int levels, int slots, int slotLimit, int rackLimit) {
        return rack(code, x, 0, levels, slots, slotLimit, rackLimit);
    }

    static RackLayout.Rack rack(String code, int x, int y, int levels, int slots, int slotLimit, int rackLimit) {
        return new RackLayout.Rack(code, value(400), value(200), value(450), value(x), value(y),
            BigDecimal.ZERO, value(3), value(8), value(levels), value(15), value(15), value(slots), value(slotLimit), value(rackLimit));
    }

    private static BigDecimal value(int value) {
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal value(String value) {
        return new BigDecimal(value);
    }
}
