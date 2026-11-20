package com.storagehub.service;

import com.storagehub.api.facility.RackLayout;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.UnitType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RackLayoutCalculator {
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal MAX_PALLET_OVERHANG_CM = StorageHubRules.MAX_PALLET_OVERHANG_CM;
    private static final BigDecimal MIN_CARGO_HEIGHT_CM = StorageHubRules.MIN_CARGO_HEIGHT_CM;
    private static final RackLayout.Sample SMALL_BOX = new RackLayout.Sample("Thùng nhỏ", 40, 30, 30, 5, 3);
    private static final RackLayout.Sample LARGE_BOX = new RackLayout.Sample("Thùng lớn", 100, 80, 80, 15, 1);

    private RackLayoutCalculator() {}

    static RackLayout.Result calculate(UnitType unit, RackLayout.Configuration configuration) {
        if (unit == null || configuration == null) {
            throw ApiExceptions.validation("Cấu hình kệ pallet chưa hợp lệ.", List.of(
                "Phải cung cấp đầy đủ thông tin gian kho và cấu hình khung kệ."
            ));
        }
        List<String> configurationErrors = new ArrayList<>();
        RackLayout.Configuration effectiveConfiguration = Boolean.TRUE.equals(configuration.autoGeneratePallet())
            ? normalizeV8Configuration(configuration, configurationErrors)
            : configuration;
        if (!configurationErrors.isEmpty()) {
            throw ApiExceptions.validation("Cấu hình kệ pallet chưa hợp lệ.", configurationErrors);
        }
        boolean v8 = Boolean.TRUE.equals(effectiveConfiguration.autoGeneratePallet());
        RackLayout.Pallet pallet = effectiveConfiguration.pallet();
        if (pallet == null || pallet.heightCm() == null
            || (pallet.emptyKg() != null && pallet.emptyKg().signum() < 0)
            || (pallet.maxPayloadKg() != null && pallet.maxPayloadKg().signum() < 0)) {
            throw ApiExceptions.validation("Cấu hình tấm kê chưa hợp lệ.", List.of(
                "Chiều cao tấm kê phải dương, khối lượng rỗng và tải hàng phải không âm."
            ));
        }
        if (!v8 && (pallet.emptyKg() == null || pallet.maxPayloadKg() == null)) {
            throw ApiExceptions.validation("Cấu hình tấm kê chưa hợp lệ.", List.of(
                "Cấu hình cũ phải có đủ khối lượng tấm kê rỗng và tải hàng tối đa."
            ));
        }
        RackLayout.Clearances clearances = effectiveConfiguration.clearances();
        List<RackLayout.RackPreview> previews = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = operationalWarnings(unit);
        String operationStatus = v8
            ? warnings.isEmpty() ? "CONFIGURED_NOT_VERIFIED" : "NOT_EVALUATED"
            : "LEGACY";
        List<Footprint> placed = new ArrayList<>();
        List<Footprint> rackFrames = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        BigDecimal obstructionHeightCm = unit.getLowestObstructionHeightM() == null
            ? null : unit.getLowestObstructionHeightM().movePointRight(2);
        BigDecimal overheadClearanceCm = unit.getOverheadClearanceM() == null
            ? null : unit.getOverheadClearanceM().movePointRight(2);
        BigDecimal gateWidthCm = unit.getGateWidthM() == null
            ? null : unit.getGateWidthM().movePointRight(2);
        BigDecimal gateHeightCm = unit.getGateHeightM() == null
            ? null : unit.getGateHeightM().movePointRight(2);
        BigDecimal warehouseHeightCm = unit.getHeightM().movePointRight(2);
        BigDecimal warehouseLengthCm = unit.getLengthM().movePointRight(2);
        BigDecimal warehouseWidthCm = unit.getWidthM().movePointRight(2);
        if ((obstructionHeightCm == null) != (overheadClearanceCm == null)) {
            errors.add("Nếu khai báo cao độ vật cản hoặc trần, phải nhập đồng thời khoảng hở an toàn phía trên kệ.");
        }
        if ((gateWidthCm == null) != (gateHeightCm == null)) {
            errors.add("Nếu khai báo kích thước cổng, phải nhập đồng thời chiều rộng và chiều cao thông thủy của cổng.");
        }
        if (obstructionHeightCm != null && obstructionHeightCm.signum() <= 0) {
            errors.add("Cao độ vật cản thấp nhất phải lớn hơn 0 cm.");
        }
        if (obstructionHeightCm != null && greater(obstructionHeightCm, warehouseHeightCm)) {
            errors.add("Cao độ vật cản thấp nhất không được cao hơn chiều cao gian kho.");
        }
        if (gateWidthCm != null && greater(gateWidthCm, warehouseWidthCm)) {
            errors.add("Chiều rộng thông thủy của cổng không được lớn hơn chiều rộng gian kho.");
        }
        if (gateHeightCm != null && greater(gateHeightCm, warehouseHeightCm)) {
            errors.add("Chiều cao thông thủy của cổng không được lớn hơn chiều cao gian kho.");
        }
        BigDecimal maximumFloorSpanCm = warehouseLengthCm.max(warehouseWidthCm);
        if (unit.getAisleWidthM() != null
            && greater(unit.getAisleWidthM().movePointRight(2), maximumFloorSpanCm)) {
            errors.add("Chiều rộng lối đi chính không thể lớn hơn kích thước gian kho.");
        }
        if (unit.getStorageAisleWidthM() != null
            && greater(unit.getStorageAisleWidthM().movePointRight(2), maximumFloorSpanCm)) {
            errors.add("Chiều rộng lối đi giữa các kệ không thể lớn hơn kích thước gian kho.");
        }
        if (overheadClearanceCm != null && overheadClearanceCm.signum() < 0) {
            errors.add("Khoảng hở an toàn phía trên kệ phải lớn hơn hoặc bằng 0 cm.");
        }
        BigDecimal usableWarehouseHeightCm = usableWarehouseHeightCm(unit, obstructionHeightCm, overheadClearanceCm);
        if (overheadClearanceCm != null && overheadClearanceCm.signum() >= 0) {
            BigDecimal limitingHeightCm = warehouseHeightCm.min(
                obstructionHeightCm == null ? warehouseHeightCm : obstructionHeightCm);
            if (overheadClearanceCm.compareTo(limitingHeightCm) >= 0) {
                errors.add("Khoảng hở an toàn phía trên kệ phải nhỏ hơn chiều cao sử dụng của gian kho.");
            }
        }
        if (usableWarehouseHeightCm != null && usableWarehouseHeightCm.signum() <= 0) {
            if (overheadClearanceCm == null || overheadClearanceCm.compareTo(
                warehouseHeightCm.min(obstructionHeightCm == null ? warehouseHeightCm : obstructionHeightCm)) < 0) {
                errors.add("Chiều cao sử dụng của kho sau khi trừ khoảng hở an toàn phải lớn hơn 0.");
            }
        }
        if (!v8 && clearances.directBeamSupport()
            && clearances.maxPalletOverhangCm().compareTo(MAX_PALLET_OVERHANG_CM) != 0) {
            errors.add("Độ nhô tối đa của tấm kê là quy định cố định 5 cm mỗi mặt rack.");
        }
        for (RackLayout.Rack rack : effectiveConfiguration.racks()) {
            String code = rack.code().strip();
            if (!codes.add(code.toUpperCase(Locale.ROOT))) errors.add(code + ": mã khung đã được sử dụng.");
            if (rack.orientationDeg().intValueExact() != 0 && rack.orientationDeg().intValueExact() != 90) {
                errors.add(code + ": hướng khung phải là 0° hoặc 90°.");
                continue;
            }
            Footprint footprint = Footprint.of(rack);
            Footprint occupied = clearances.directBeamSupport()
                ? footprint.expandedAcross(MAX_PALLET_OVERHANG_CM, rack.orientationDeg().intValueExact() == 90)
                : footprint;
            BigDecimal floorLength = unit.getLengthM().movePointRight(2);
            BigDecimal floorWidth = unit.getWidthM().movePointRight(2);
            BigDecimal wall = clearances.rackWallClearanceCm();
            boolean rackTooCloseToWall = footprint.x().compareTo(wall) < 0
                || footprint.y().compareTo(wall) < 0
                || greater(footprint.endX(), floorLength.subtract(wall))
                || greater(footprint.endY(), floorWidth.subtract(wall));
            boolean palletOutsideFloor = occupied.x().signum() < 0 || occupied.y().signum() < 0
                || greater(occupied.endX(), floorLength) || greater(occupied.endY(), floorWidth);
            if (rackTooCloseToWall || palletOutsideFloor) {
                errors.add(code + ": khung hoặc phần nhô của tấm kê không giữ đủ khoảng cách với tường kho.");
            }
            if (greater(rack.heightCm(), warehouseHeightCm)) {
                errors.add(code + ": chiều cao khung " + cm(rack.heightCm())
                    + " vượt chiều cao kho " + cm(warehouseHeightCm) + ".");
            }
            if (usableWarehouseHeightCm != null && greater(rack.heightCm(), usableWarehouseHeightCm)) {
                errors.add(code + ": chiều cao sử dụng của kho chỉ còn " + cm(usableWarehouseHeightCm)
                    + " sau khi trừ khoảng hở an toàn phía trên kệ, nhỏ hơn chiều cao khung "
                    + cm(rack.heightCm()) + ".");
            }
            for (Footprint other : placed) {
                if (occupied.overlaps(other)) errors.add(code + ": chồng vị trí với khung " + other.code() + ".");
            }
            placed.add(occupied);
            rackFrames.add(footprint);
            RackLayout.RackPreview preview = calculateRack(rack, clearances, pallet, errors, v8, unit, operationStatus);
            if (preview != null) previews.add(preview);
        }
        if (v8) validateOperationalEnvelope(unit, effectiveConfiguration.racks(), rackFrames, previews, pallet, errors);
        if (!errors.isEmpty()) {
            throw ApiExceptions.validation("Cấu hình kệ pallet chưa hợp lệ.", errors);
        }
        int slots = previews.stream().mapToInt(RackLayout.RackPreview::palletSlotCount).sum();
        BigDecimal totalEmptyPalletWeight = BigDecimal.valueOf(slots).multiply(pallet.emptyKg());
        boolean palletRatingConfigured = pallet.emptyKg().signum() > 0 && pallet.maxPayloadKg().signum() > 0;
        if (!v8 && unit.getMaxStoredLoadKg() != null
            && totalEmptyPalletWeight.compareTo(unit.getMaxStoredLoadKg()) >= 0) {
            throw ApiExceptions.validation("Cấu hình kệ pallet chưa hợp lệ.", List.of(
                "Tổng khối lượng tấm kê rỗng " + totalEmptyPalletWeight.stripTrailingZeros().toPlainString()
                    + " kg đã bằng hoặc vượt giới hạn tổng pallet và hàng của gian kho "
                    + unit.getMaxStoredLoadKg().stripTrailingZeros().toPlainString() + " kg."
            ));
        }
        int small = previews.stream().mapToInt(RackLayout.RackPreview::smallBoxesPerRack).sum();
        int large = previews.stream().mapToInt(RackLayout.RackPreview::largeBoxesPerRack).sum();
        int compartments = previews.stream().mapToInt(RackLayout.RackPreview::compartmentCount).sum();
        BigDecimal physicalCargoLimit = java.util.stream.IntStream.range(0, previews.size())
            .mapToObj(index -> cargoLoadForRack(previews.get(index), effectiveConfiguration.racks().get(index), pallet))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossCargoLimit = !v8 && unit.getMaxStoredLoadKg() != null
            ? unit.getMaxStoredLoadKg().subtract(totalEmptyPalletWeight).max(BigDecimal.ZERO)
            : physicalCargoLimit;
        BigDecimal unitCargoLimit = clearances.directBeamSupport() && palletRatingConfigured
            ? unit.getMaxLoadKg().min(physicalCargoLimit).min(grossCargoLimit)
            : clearances.directBeamSupport() ? BigDecimal.ZERO : unit.getMaxLoadKg();
        int rackCount = previews.size();
        RackLayout.RackPreview first = previews.getFirst();
        RackLayout.Rack firstInput = effectiveConfiguration.racks().getFirst();
        BigDecimal maxCargoHeight = previews.stream()
            .map(RackLayout.RackPreview::maxCargoHeightCm)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        BigDecimal maxCargoPerCompartment = java.util.stream.IntStream.range(0, previews.size())
            .mapToObj(index -> cargoLoadForCompartment(previews.get(index).maxBeamPairLoadKg(), effectiveConfiguration.racks().get(index), pallet))
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        BigDecimal maxCargoPerRack = java.util.stream.IntStream.range(0, previews.size())
            .mapToObj(index -> cargoLoadForRack(previews.get(index), effectiveConfiguration.racks().get(index), pallet))
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        int maxLevelsByHeight = effectiveConfiguration.racks().stream()
            .mapToInt(rack -> maxLevelCount(rack, pallet, clearances))
            .min().orElse(0);
        BigDecimal effectiveRackLoad = previews.stream()
            .map(RackLayout.RackPreview::effectiveRackLoadKg)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        int maxPalletCountByGeometry = previews.stream()
            .mapToInt(RackLayout.RackPreview::maxPalletCountByGeometry)
            .min().orElse(0);
        int maxPalletCountByLoad = maxPalletCountByLoad(effectiveConfiguration, pallet, clearances);
        BigDecimal maxStoredLoadAtCapacityKg = totalEmptyPalletWeight.add(unitCargoLimit);
        PalletRatingEvaluation ratingEvaluation = evaluatePalletRatings(
            unit, effectiveConfiguration, maxPalletCountByGeometry);
        String geometryStatus = v8 ? "CONFIG_GEOMETRY_VALID" : "LEGACY_CONFIGURATION";
        return new RackLayout.Result(effectiveConfiguration,
            v8 ? StorageHubRules.RULES_VERSION : "legacy", List.copyOf(previews), slots,
            underFloorQuota(small, unitCargoLimit, SMALL_BOX), underFloorQuota(large, unitCargoLimit, LARGE_BOX),
            SMALL_BOX, LARGE_BOX, unit.getLengthM().multiply(unit.getWidthM()), unitCargoLimit,
            MIN_CARGO_HEIGHT_CM, pallet.heightCm(), pallet.emptyKg(),
            pallet.maxPayloadKg(), rackCount, first.levels().size(), maxLevelsByHeight, first.bayCount(), compartments, slots,
            first.bayLengthCm(), first.bayWidthCm(), first.levels().getFirst().clearHeightCm(),
            BigDecimal.valueOf(pallet.lengthCm()), BigDecimal.valueOf(pallet.widthCm()), pallet.emptyKg(),
            totalEmptyPalletWeight,
            pallet.maxPayloadKg(), first.maxBeamPairLoadKg(), firstInput.maxRackLoadKg(), effectiveRackLoad,
            BigDecimal.valueOf(pallet.lengthCm()), BigDecimal.valueOf(pallet.widthCm()), maxCargoHeight,
            maxCargoPerCompartment, maxCargoPerRack, unitCargoLimit, maxStoredLoadAtCapacityKg,
            maxPalletCountByLoad, maxPalletCountByGeometry,
            first.actualPalletOverhangCm(), ratingEvaluation.maxFeasiblePalletCount(), ratingEvaluation.options(),
            geometryStatus, operationStatus, palletRatingConfigured, "NOT_EVALUATED", warnings, null, null, null);
    }

    private static PalletRatingEvaluation evaluatePalletRatings(
        UnitType unit, RackLayout.Configuration configuration, int maxGeometry) {
        if (maxGeometry <= 0) return new PalletRatingEvaluation(0, List.of());
        List<RackLayout.PalletRatingOption> options = new ArrayList<>();
        int feasibleMaximum = 0;
        boolean everyRatingKnown = true;
        for (int count = 1; count <= maxGeometry; count++) {
            RackLayout.Pallet geometry = null;
            boolean geometryValid = true;
            for (RackLayout.Rack rack : configuration.racks()) {
                RackLayout.Rack candidateRack = withPalletCount(rack, count);
                List<String> candidateErrors = new ArrayList<>();
                RackLayout.Pallet candidate = generatePallet(candidateRack, candidateErrors);
                if (candidate == null || !candidateErrors.isEmpty()) {
                    geometryValid = false;
                    break;
                }
                if (geometry == null) geometry = candidate;
                else if (!samePalletGeometry(geometry, candidate)) geometryValid = false;
            }
            if (!geometryValid || geometry == null) {
                everyRatingKnown = false;
                options.add(new RackLayout.PalletRatingOption(count, 0, 0, StorageHubRules.PALLET_HEIGHT_CM.intValueExact(),
                    false, false, null, null, "Không tạo được kích thước tấm kê đồng nhất."));
                continue;
            }
            RackLayout.Pallet rating = geometry;
            boolean configured = rating != null && rating.emptyKg() != null && rating.maxPayloadKg() != null
                && rating.emptyKg().signum() > 0 && rating.maxPayloadKg().signum() > 0;
            boolean feasible = configured && candidateRatingFits(unit, configuration.racks(), configuration.clearances(), count,
                rating, Boolean.TRUE.equals(configuration.autoGeneratePallet()));
            String reason = !configured
                ? "Chưa có thông số tấm kê cố định."
                : feasible ? "Đạt hình học và tải trọng theo cấu hình tự động."
                : "Số tấm kê này vượt tải cặp dầm, khung hoặc giới hạn tổng gian.";
            if (!configured) everyRatingKnown = false;
            if (feasible) feasibleMaximum = count;
            options.add(new RackLayout.PalletRatingOption(count, geometry.lengthCm(), geometry.widthCm(),
                geometry.heightCm().intValueExact(), configured, feasible, configured ? rating.emptyKg() : null,
                configured ? rating.maxPayloadKg() : null, reason));
        }
        return new PalletRatingEvaluation(everyRatingKnown ? feasibleMaximum : null, List.copyOf(options));
    }

    private static boolean candidateRatingFits(UnitType unit, List<RackLayout.Rack> racks,
                                               RackLayout.Clearances clearances, int palletCount,
                                               RackLayout.Pallet rating, boolean v8) {
        BigDecimal totalTare = BigDecimal.ZERO;
        for (RackLayout.Rack rack : racks) {
            int bays = rack.uprightFrameCount().intValueExact() - 1;
            int levels = rack.levelCount().intValueExact();
            int compartments = bays * levels;
            BigDecimal compartmentTare = rating.emptyKg().multiply(BigDecimal.valueOf(palletCount));
            if (effectiveBeamPairLoad(rack, clearances).compareTo(compartmentTare) <= 0) return false;
            BigDecimal rackTare = compartmentTare.multiply(BigDecimal.valueOf(compartments));
            if (rack.maxRackLoadKg().compareTo(rackTare) <= 0) return false;
            totalTare = totalTare.add(rackTare);
        }
        return v8 || unit.getMaxStoredLoadKg() == null
            || totalTare.compareTo(unit.getMaxStoredLoadKg()) < 0;
    }

    private static RackLayout.Rack withPalletCount(RackLayout.Rack source, int count) {
        return new RackLayout.Rack(source.code(), source.lengthCm(), source.widthCm(), source.heightCm(),
            source.xCm(), source.yCm(), source.orientationDeg(), source.uprightFrameCount(),
            source.uprightSizeCm(), source.levelCount(), source.beamHeightCm(), source.firstBeamBottomCm(),
            BigDecimal.valueOf(count), source.maxLoadedPalletKg(), source.maxRackLoadKg(),
            source.beamOuterSpanCm(), source.maxBeamPairLoadKg());
    }

    private record PalletRatingEvaluation(Integer maxFeasiblePalletCount,
                                          List<RackLayout.PalletRatingOption> options) {}

    /**
     * Chuẩn hóa cấu hình v8. Manager cung cấp hình học khung, số tầng, số bộ
     * trụ, số tấm kê trong một khoang và các mức tải kết cấu. Các quy tắc
     * hình học cùng thông số tấm kê đã chốt được Backend tính lại ở đây.
     */
    private static RackLayout.Configuration normalizeV8Configuration(
        RackLayout.Configuration source, List<String> errors) {
        if (source.racks() == null || source.racks().isEmpty()) return source;
        if ("AUTO_BALANCED_TWO_ROWS".equalsIgnoreCase(source.layoutMode())
            && source.requestedRackCount() != null
            && source.requestedRackCount() != source.racks().size()) {
            errors.add("Số khung được sinh tự động phải đúng bằng số khung Manager yêu cầu; không tự giảm số khung.");
            return source;
        }

        List<RackLayout.Rack> normalizedRacks = new ArrayList<>();
        RackLayout.Pallet generated = null;
        for (RackLayout.Rack sourceRack : source.racks()) {
            RackLayout.Pallet geometryPallet = generatePallet(sourceRack, errors);
            if (geometryPallet == null) continue;
            RackLayout.Pallet generatedForRack = geometryPallet;
            if (generated == null) {
                generated = generatedForRack;
            } else if (!samePalletGeometry(generated, generatedForRack)) {
                errors.add(sourceRack.code() + ": các khung trong cùng một loại gian phải tạo ra cùng kích thước tấm kê.");
            }
            // maxLoadedPalletKg is a historical field. It must not silently
            // become the v8.1 beam-pair limit when a new request omits the
            // real structural limit.
            BigDecimal beamPairLoad = sourceRack.maxBeamPairLoadKg();
            if (beamPairLoad == null) {
                errors.add(sourceRack.code() + ": cần khai báo giới hạn tải của mỗi khoang/cặp dầm bằng maxBeamPairLoadKg; trường maxLoadedPalletKg chỉ dùng để đọc dữ liệu cũ.");
                continue;
            }
            BigDecimal rackLoad = sourceRack.maxRackLoadKg();
            normalizedRacks.add(new RackLayout.Rack(
                sourceRack.code(), sourceRack.lengthCm(), sourceRack.widthCm(), sourceRack.heightCm(),
                sourceRack.xCm(), sourceRack.yCm(), sourceRack.orientationDeg(),
                sourceRack.uprightFrameCount(), StorageHubRules.UPRIGHT_SIZE_CM,
                sourceRack.levelCount(), StorageHubRules.BEAM_HEIGHT_CM,
                StorageHubRules.FIRST_BEAM_BOTTOM_CM, sourceRack.palletsPerCompartment(),
                beamPairLoad, rackLoad, sourceRack.widthCm(), beamPairLoad));
        }
        if (generated == null) return source;
        RackLayout.Clearances fixed = new RackLayout.Clearances(
            StorageHubRules.PALLET_GAP_CM, StorageHubRules.PALLET_END_CLEARANCE_CM, BigDecimal.ZERO,
            StorageHubRules.HANDLING_CLEARANCE_CM, StorageHubRules.MAX_PALLET_OVERHANG_CM,
            StorageHubRules.WALL_CLEARANCE_CM);
        // Pallet dimensions and load values are generated from the approved
        // pallet rules. Manager input is limited to the pallet count.
        return new RackLayout.Configuration(fixed, List.copyOf(normalizedRacks), generated, true, List.of(),
            source.requestedRackCount(), source.layoutMode());
    }

    private static RackLayout.Pallet generatePallet(RackLayout.Rack rack, List<String> errors) {
        int frames;
        int pallets;
        try {
            frames = rack.uprightFrameCount().intValueExact();
            pallets = rack.palletsPerCompartment().intValueExact();
        } catch (ArithmeticException exception) {
            errors.add(rack.code() + ": số bộ trụ và số tấm kê mỗi khoang phải là số nguyên.");
            return null;
        }
        int bays = frames - 1;
        if (bays <= 0) {
            errors.add(rack.code() + ": số bộ trụ phải lớn hơn 1 để tạo khoang.");
            return null;
        }
        BigDecimal clearNumerator = rack.lengthCm()
            .subtract(StorageHubRules.UPRIGHT_SIZE_CM.multiply(BigDecimal.valueOf(frames)));
        BigDecimal bayLength = clearNumerator.divide(BigDecimal.valueOf(bays), 12, RoundingMode.DOWN);
        BigDecimal maxNumerator = clearNumerator.subtract(BigDecimal.valueOf(bays)
            .multiply(StorageHubRules.PALLET_END_CLEARANCE_CM.multiply(TWO)
                .subtract(StorageHubRules.PALLET_GAP_CM)));
        BigDecimal maxDenominator = BigDecimal.valueOf(bays)
            .multiply(StorageHubRules.MIN_PALLET_WIDTH_CM.add(StorageHubRules.PALLET_GAP_CM));
        int maxByGeometry = maxNumerator.signum() > 0
            ? maxNumerator.divideToIntegralValue(maxDenominator).intValue()
            : 0;
        if (pallets > maxByGeometry) {
            errors.add(rack.code() + ": số tấm kê mỗi khoang là " + pallets
                + ", nhưng kích thước khoang hiện tại chỉ bố trí được tối đa " + maxByGeometry + " tấm kê mỗi khoang.");
        }
        BigDecimal numerator = clearNumerator.subtract(BigDecimal.valueOf(bays).multiply(
            StorageHubRules.PALLET_END_CLEARANCE_CM.multiply(TWO)
                .add(StorageHubRules.PALLET_GAP_CM.multiply(BigDecimal.valueOf(pallets - 1)))));
        BigDecimal widthDenominator = BigDecimal.valueOf(bays).multiply(BigDecimal.valueOf(pallets));
        BigDecimal maxPalletWidth = pallets > 0
            ? numerator.divide(widthDenominator, 12, RoundingMode.DOWN)
            : BigDecimal.ZERO;
        if (maxPalletWidth.compareTo(StorageHubRules.MIN_PALLET_WIDTH_CM) < 0) {
            errors.add(rack.code() + ": chiều rộng tấm kê tính được là " + cm(maxPalletWidth)
                + ", nhỏ hơn mức tối thiểu " + cm(StorageHubRules.MIN_PALLET_WIDTH_CM) + ".");
        }
        BigDecimal palletLength = rack.widthCm().add(StorageHubRules.MAX_PALLET_OVERHANG_CM.multiply(TWO));
        if (palletLength.stripTrailingZeros().scale() > 0 || maxPalletWidth.signum() < 0) {
            errors.add(rack.code() + ": chiều rộng ngoài khung và kích thước tấm kê phải quy đổi được theo cm nguyên.");
            return null;
        }
        int length = palletLength.intValueExact();
        int width = maxPalletWidth.setScale(0, RoundingMode.DOWN).intValue();
        return new RackLayout.Pallet(length, width, StorageHubRules.PALLET_HEIGHT_CM,
            StorageHubRules.calculatePalletWeightKg(BigDecimal.valueOf(length), BigDecimal.valueOf(width)),
            StorageHubRules.PALLET_MAX_PAYLOAD_KG);
    }

    private static boolean samePalletGeometry(RackLayout.Pallet first, RackLayout.Pallet second) {
        return first.lengthCm().equals(second.lengthCm())
            && first.widthCm().equals(second.widthCm())
            && first.heightCm().compareTo(second.heightCm()) == 0;
    }

    static BigDecimal cargoLoadForRack(RackLayout.RackPreview preview, RackLayout.Rack rack,
                                       RackLayout.Pallet pallet) {
        return cargoLoadForRack(preview.effectiveRackLoadKg(), preview.maxBeamPairLoadKg(),
            preview.compartmentCount(), rack, pallet);
    }

    private static BigDecimal cargoLoadForRack(BigDecimal effectiveRackLoad, BigDecimal beamPairLoad,
                                               int compartmentCount, RackLayout.Rack rack,
                                               RackLayout.Pallet pallet) {
        BigDecimal compartments = BigDecimal.valueOf(compartmentCount);
        BigDecimal palletCount = rack.palletsPerCompartment();
        BigDecimal slotCount = compartments.multiply(palletCount);
        BigDecimal cargoByRackLoad = effectiveRackLoad
            .subtract(pallet.emptyKg().multiply(slotCount));
        BigDecimal cargoByBeamLoad = cargoLoadForCompartment(beamPairLoad, rack, pallet)
            .multiply(compartments);
        BigDecimal cargoByPalletPayload = pallet.maxPayloadKg().multiply(slotCount);
        return cargoByRackLoad.min(cargoByBeamLoad).min(cargoByPalletPayload).max(BigDecimal.ZERO);
    }

    static BigDecimal cargoLoadForCompartment(BigDecimal beamPairLoad, RackLayout.Rack rack,
                                              RackLayout.Pallet pallet) {
        BigDecimal palletCount = rack.palletsPerCompartment();
        BigDecimal emptyTotal = pallet.emptyKg().multiply(palletCount);
        BigDecimal payloadTotal = pallet.maxPayloadKg().multiply(palletCount);
        return beamPairLoad.subtract(emptyTotal).min(payloadTotal).max(BigDecimal.ZERO);
    }

    private static RackLayout.RackPreview calculateRack(RackLayout.Rack rack, RackLayout.Clearances rules,
                                                        RackLayout.Pallet pallet, List<String> errors,
                                                        boolean v8, UnitType unit, String operationStatus) {
        int priorErrors = errors.size();
        String prefix = rack.code().strip() + ": ";
        int bays;
        int levelCount;
        int palletCount;
        try {
            bays = rack.uprightFrameCount().intValueExact() - 1;
            levelCount = rack.levelCount().intValueExact();
            palletCount = rack.palletsPerCompartment().intValueExact();
        } catch (ArithmeticException exception) {
            errors.add(prefix + "số bộ trụ, số tầng và số tấm kê mỗi khoang phải là số nguyên.");
            return null;
        }
        if (levelCount < 1) {
            errors.add(prefix + "số tầng phải từ 1 trở lên.");
            return null;
        }
        int maxLevelCount = maxLevelCount(rack, pallet, rules);
        if (levelCount > maxLevelCount) {
            // The maximum is derived from the rack height and the fixed
            // vertical clearances; it is not a global three-level limit.
            errors.add(prefix + "số tầng là " + levelCount
                + ", nhưng chiều cao khung hiện tại chỉ sử dụng được tối đa " + maxLevelCount + " tầng.");
        }
        int compartments = bays * levelCount;
        int slots = compartments * palletCount;
        BigDecimal bayLength = divide(rack.lengthCm().subtract(rack.uprightSizeCm().multiply(rack.uprightFrameCount())), bays, v8);
        BigDecimal bayWidth = rack.widthCm().subtract(rack.uprightSizeCm().multiply(TWO));
        BigDecimal levelHeight = divide(rack.heightCm().subtract(rack.firstBeamBottomCm())
            .subtract(rack.beamHeightCm().multiply(rack.levelCount())), levelCount, v8);
        if (bayLength.signum() <= 0 || bayWidth.signum() <= 0 || levelHeight.signum() <= 0) {
            errors.add(prefix + "kích thước thông thủy phải dương sau khi trừ trụ và dầm.");
            return null;
        }
        BigDecimal geometryCargoHeight = levelHeight.subtract(pallet.heightCm()).subtract(rules.handlingCm());
        if (greater(MIN_CARGO_HEIGHT_CM, geometryCargoHeight)) {
            errors.add(prefix + "chiều cao dành cho hàng còn " + cm(geometryCargoHeight)
                + ", cần tối thiểu " + cm(MIN_CARGO_HEIGHT_CM) + " theo quy tắc mô phỏng.");
        }
        int length = pallet.lengthCm();
        int width = pallet.widthCm();
        boolean fitsLengthwise = rules.directBeamSupport()
            ? fitsDirectBeam(pallet, rack, rules, bayLength, palletCount)
            : fitsPallets(length, width, palletCount, rules, bayLength, bayWidth);
        boolean fitsRotated = rules.directBeamSupport()
            ? false
            : fitsPallets(width, length, palletCount, rules, bayLength, bayWidth);
        if (!fitsLengthwise && !fitsRotated) {
            if (rules.directBeamSupport()) {
                errors.add(prefix + directPalletIssue(pallet, rack, rules, bayLength, palletCount));
            } else {
                errors.add(prefix + rack.palletsPerCompartment() + " pallet cùng khoảng hở cần ít nhất "
                    + requiredFootprint(length, width, palletCount, rules) + " hoặc "
                    + requiredFootprint(width, length, palletCount, rules)
                    + ". Khoang hiện có " + cm(bayLength) + " × " + cm(bayWidth) + ".");
            }
        }
        BigDecimal legacyPositionLoad = rack.maxLoadedPalletKg() != null
            ? rack.maxLoadedPalletKg() : effectiveBeamPairLoad(rack, rules);
        if (!v8 && !greater(legacyPositionLoad, pallet.emptyKg())) {
            errors.add(prefix + "giới hạn tải từng vị trí phải lớn hơn khối lượng pallet rỗng ("
                + pallet.emptyKg().stripTrailingZeros().toPlainString().replace('.', ',') + " kg).");
        }
        if (!greater(rack.maxRackLoadKg(), pallet.emptyKg().multiply(BigDecimal.valueOf(slots)))) {
            BigDecimal totalRackTare = pallet.emptyKg().multiply(BigDecimal.valueOf(slots));
            errors.add(prefix + "giới hạn tải mỗi khung phải lớn hơn tổng khối lượng " + slots
                + " tấm kê rỗng (" + kg(totalRackTare) + ").");
        }
        BigDecimal beamPairLoad = effectiveBeamPairLoad(rack, rules);
        if (!greater(beamPairLoad, pallet.emptyKg().multiply(BigDecimal.valueOf(palletCount)))) {
            BigDecimal compartmentTare = pallet.emptyKg().multiply(BigDecimal.valueOf(palletCount));
            errors.add(prefix + "giới hạn tải mỗi khoang phải lớn hơn tổng khối lượng " + palletCount
                + " tấm kê rỗng (" + kg(compartmentTare) + ").");
        }
        if (!greater(effectiveRackLoad(rack, slots, rules), pallet.emptyKg().multiply(BigDecimal.valueOf(slots)))) {
            BigDecimal totalRackTare = pallet.emptyKg().multiply(BigDecimal.valueOf(slots));
            errors.add(prefix + "tải hiệu dụng của khung hiện là " + kg(effectiveRackLoad(rack, slots, rules))
                + ", nhưng phải lớn hơn tổng khối lượng tấm kê rỗng " + kg(totalRackTare) + ".");
        }
        if (errors.size() != priorErrors) return null;

        List<RackLayout.Level> levels = new ArrayList<>();
        BigDecimal supportTop = rack.firstBeamBottomCm().add(rack.beamHeightCm());
        BigDecimal cargoHeight = null;
        BigDecimal obstructionHeightCm = unit.getLowestObstructionHeightM() == null
            ? null : unit.getLowestObstructionHeightM().movePointRight(2);
        BigDecimal overheadClearanceCm = unit.getOverheadClearanceM() == null
            ? null : unit.getOverheadClearanceM().movePointRight(2);
        for (int level = 1; level <= levelCount; level++) {
            BigDecimal levelCargoHeight = geometryCargoHeight;
            if (obstructionHeightCm != null && overheadClearanceCm != null) {
                levelCargoHeight = levelCargoHeight.min(obstructionHeightCm.subtract(overheadClearanceCm)
                    .subtract(supportTop).subtract(pallet.heightCm()));
            }
            if (greater(MIN_CARGO_HEIGHT_CM, levelCargoHeight)) {
                errors.add(prefix + "tầng " + level + " chỉ còn chiều cao dành cho hàng "
                    + cm(levelCargoHeight) + ", cần tối thiểu " + cm(MIN_CARGO_HEIGHT_CM) + ".");
            }
            levels.add(new RackLayout.Level(level, supportTop, levelHeight, levelCargoHeight));
            cargoHeight = cargoHeight == null ? levelCargoHeight : cargoHeight.min(levelCargoHeight);
            supportTop = supportTop.add(levelHeight).add(rack.beamHeightCm());
        }
        if (!errors.isEmpty() && errors.size() > priorErrors) return null;
        int maxPalletCountByGeometry = maxPalletCountByGeometry(rack);
        BigDecimal effectiveRackLoad = effectiveRackLoad(rack, slots, rules);
        BigDecimal cargoPerCompartment = cargoLoadForCompartment(beamPairLoad, rack, pallet);
        BigDecimal cargoPerRack = cargoLoadForRack(effectiveRackLoad, beamPairLoad, compartments, rack, pallet);
        // This is the per-pallet geometric/payload capacity. The shared beam
        // pair is applied separately by boxesPerRack, so N pallets in one
        // compartment cannot accidentally multiply the same beam budget.
        BigDecimal samplePalletLoad = pallet.emptyKg().add(pallet.maxPayloadKg());
        int small = PalletPacking.palletCapacity(pallet, PalletPacking.Cargo.from(SMALL_BOX), cargoHeight,
            v8 ? samplePalletLoad : beamPairLoad).maxPieces();
        int large = PalletPacking.palletCapacity(pallet, PalletPacking.Cargo.from(LARGE_BOX), cargoHeight,
            v8 ? samplePalletLoad : beamPairLoad).maxPieces();
        return new RackLayout.RackPreview(rack.code().strip(), bays, List.copyOf(levels), compartments, slots,
            bayLength, bayWidth, cargoHeight,
            rules.directBeamSupport() ? width : (fitsLengthwise ? length : width),
            rules.directBeamSupport() ? length : (fitsLengthwise ? width : length), small, large,
            boxesPerRack(SMALL_BOX, small, palletCount, compartments, cargoPerCompartment, cargoPerRack),
            boxesPerRack(LARGE_BOX, large, palletCount, compartments, cargoPerCompartment, cargoPerRack),
            effectiveRackLoad, beamPairLoad, maxPalletCountByGeometry,
            BigDecimal.valueOf(pallet.lengthCm()).subtract(rack.widthCm()).divide(TWO, 6, RoundingMode.DOWN),
            v8 ? "CONFIG_GEOMETRY_VALID" : "LEGACY_CONFIGURATION",
            operationStatus);
    }

    private static void validateOperationalEnvelope(UnitType unit, List<RackLayout.Rack> racks,
                                                     List<Footprint> frames,
                                                     List<RackLayout.RackPreview> previews,
                                                     RackLayout.Pallet pallet,
                                                     List<String> errors) {
        BigDecimal storageAisleCm = unit.getStorageAisleWidthM() == null
            ? null : unit.getStorageAisleWidthM().movePointRight(2);
        BigDecimal mainAisleCm = unit.getAisleWidthM() == null
            ? null : unit.getAisleWidthM().movePointRight(2);
        for (int left = 0; left < frames.size(); left++) {
            for (int right = left + 1; right < frames.size(); right++) {
                Footprint first = frames.get(left);
                Footprint second = frames.get(right);
                RackLayout.Rack rack = racks.get(left);
                if (overlaps(first.y(), first.endY(), second.y(), second.endY())) {
                    BigDecimal gap = axisGap(first.x(), first.endX(), second.x(), second.endX());
                    if (storageAisleCm != null && gap.compareTo(storageAisleCm) < 0) {
                        errors.add(rack.code().strip() + ": khoảng cách khung kệ theo lối dọc chỉ còn "
                            + cm(gap) + ", nhỏ hơn mức thiết kế " + cm(storageAisleCm) + ".");
                    }
                }
                if (overlaps(first.x(), first.endX(), second.x(), second.endX())) {
                    BigDecimal gap = axisGap(first.y(), first.endY(), second.y(), second.endY());
                    if (mainAisleCm != null && gap.compareTo(mainAisleCm) < 0) {
                        errors.add(rack.code().strip() + ": lối chính giữa các dãy kệ chỉ còn "
                            + cm(gap) + ", nhỏ hơn mức thiết kế " + cm(mainAisleCm) + ".");
                    }
                }
            }
        }
        BigDecimal gateWidthCm = unit.getGateWidthM() == null ? null : unit.getGateWidthM().movePointRight(2);
        BigDecimal gateHeightCm = unit.getGateHeightM() == null ? null : unit.getGateHeightM().movePointRight(2);
        BigDecimal highestCargoHeight = previews.stream()
            .map(RackLayout.RackPreview::maxCargoHeightCm)
            .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (gateWidthCm != null && BigDecimal.valueOf(pallet.lengthCm()).compareTo(gateWidthCm) > 0) {
            errors.add("Chiều rộng thông thủy cổng " + cm(gateWidthCm)
                + " nhỏ hơn chiều dài bắc ngang của tấm kê " + pallet.lengthCm() + " cm.");
        }
        if (gateHeightCm != null && pallet.heightCm().add(highestCargoHeight).compareTo(gateHeightCm) > 0) {
            errors.add("Chiều cao thông thủy cổng " + cm(gateHeightCm)
                + " nhỏ hơn chiều cao tấm kê và hàng mẫu cần đưa qua cổng.");
        }
    }

    private static List<String> operationalWarnings(UnitType unit) {
        List<String> warnings = new ArrayList<>();
        if (unit.getStorageAisleWidthM() == null) {
            warnings.add("Chưa khai báo khoảng cách tối thiểu giữa các mép khung kệ; mới chỉ kiểm tra chồng lấn hình học.");
        }
        boolean gateWidth = unit.getGateWidthM() != null;
        boolean gateHeight = unit.getGateHeightM() != null;
        if (!gateWidth && !gateHeight) {
            warnings.add("Chưa khai báo kích thước cổng; chưa đánh giá khả năng đưa pallet và hàng qua cổng.");
        }
        boolean obstruction = unit.getLowestObstructionHeightM() != null;
        boolean clearance = unit.getOverheadClearanceM() != null;
        if (!obstruction && !clearance) {
            warnings.add("Chưa khai báo cao độ vật cản/trần và khoảng hở phía trên; chiều cao đang được kiểm tra theo chiều cao kho.");
        }
        return List.copyOf(warnings);
    }

    private static boolean overlaps(BigDecimal start, BigDecimal end,
                                    BigDecimal otherStart, BigDecimal otherEnd) {
        return end.compareTo(otherStart) > 0 && otherEnd.compareTo(start) > 0;
    }

    private static BigDecimal axisGap(BigDecimal start, BigDecimal end,
                                      BigDecimal otherStart, BigDecimal otherEnd) {
        if (end.compareTo(otherStart) <= 0) return otherStart.subtract(end);
        if (otherEnd.compareTo(start) <= 0) return start.subtract(otherEnd);
        return BigDecimal.ZERO;
    }

    private static int maxPalletCountByGeometry(BigDecimal bayLength) {
        BigDecimal numerator = bayLength.subtract(StorageHubRules.PALLET_END_CLEARANCE_CM.multiply(TWO))
            .add(StorageHubRules.PALLET_GAP_CM);
        BigDecimal denominator = StorageHubRules.MIN_PALLET_WIDTH_CM.add(StorageHubRules.PALLET_GAP_CM);
        return numerator.signum() > 0
            ? numerator.divideToIntegralValue(denominator).intValue()
            : 0;
    }

    private static int maxPalletCountByLoad(RackLayout.Configuration configuration,
                                            RackLayout.Pallet pallet, RackLayout.Clearances clearances) {
        int maximum = 0;
        for (int palletCount = 1; palletCount <= 20; palletCount++) {
            boolean fits = true;
            for (RackLayout.Rack rack : configuration.racks()) {
                int compartments = (rack.uprightFrameCount().intValueExact() - 1)
                    * rack.levelCount().intValueExact();
                int slots = compartments * palletCount;
                BigDecimal tarePerCompartment = pallet.emptyKg().multiply(BigDecimal.valueOf(palletCount));
                BigDecimal tarePerRack = pallet.emptyKg().multiply(BigDecimal.valueOf(slots));
                BigDecimal effectiveRackLoad = rack.maxRackLoadKg().min(
                    effectiveBeamPairLoad(rack, clearances).multiply(BigDecimal.valueOf(compartments)));
                if (effectiveBeamPairLoad(rack, clearances).compareTo(tarePerCompartment) <= 0
                    || effectiveRackLoad.compareTo(tarePerRack) <= 0) {
                    fits = false;
                    break;
                }
            }
            if (!fits) break;
            maximum = palletCount;
        }
        return maximum;
    }

    static int maxLevelCount(RackLayout.Rack rack, RackLayout.Pallet pallet,
                             RackLayout.Clearances clearances) {
        BigDecimal perLevel = rack.beamHeightCm()
            .add(pallet.heightCm())
            .add(clearances.handlingCm())
            .add(MIN_CARGO_HEIGHT_CM);
        BigDecimal available = rack.heightCm().subtract(rack.firstBeamBottomCm());
        return perLevel.signum() > 0 && available.signum() > 0
            ? available.divideToIntegralValue(perLevel).intValue()
            : 0;
    }

    private static BigDecimal usableWarehouseHeightCm(UnitType unit,
                                                       BigDecimal obstructionHeightCm,
                                                       BigDecimal overheadClearanceCm) {
        if (obstructionHeightCm == null || overheadClearanceCm == null) return null;
        return unit.getHeightM().movePointRight(2)
            .min(obstructionHeightCm)
            .subtract(overheadClearanceCm);
    }

    private static int maxPalletCountByGeometry(RackLayout.Rack rack) {
        int frames = rack.uprightFrameCount().intValueExact();
        int bays = frames - 1;
        BigDecimal clearNumerator = rack.lengthCm()
            .subtract(StorageHubRules.UPRIGHT_SIZE_CM.multiply(BigDecimal.valueOf(frames)));
        BigDecimal numerator = clearNumerator.subtract(BigDecimal.valueOf(bays)
            .multiply(StorageHubRules.PALLET_END_CLEARANCE_CM.multiply(TWO)
                .subtract(StorageHubRules.PALLET_GAP_CM)));
        BigDecimal denominator = BigDecimal.valueOf(bays)
            .multiply(StorageHubRules.MIN_PALLET_WIDTH_CM.add(StorageHubRules.PALLET_GAP_CM));
        return bays > 0 && numerator.signum() > 0
            ? numerator.divideToIntegralValue(denominator).intValue()
            : 0;
    }

    static BigDecimal effectiveBeamPairLoad(RackLayout.Rack rack, RackLayout.Clearances rules) {
        // Legacy snapshots may only have maxLoadedPalletKg. New v8.1
        // configurations are rejected earlier when maxBeamPairLoadKg is
        // absent, so this fallback is deliberately limited to old layouts.
        return rack.maxBeamPairLoadKg() != null
            ? rack.maxBeamPairLoadKg() : rack.maxLoadedPalletKg();
    }

    static BigDecimal effectiveRackLoad(RackLayout.Rack rack, int slots, RackLayout.Clearances rules) {
        int palletsPerCompartment = rack.palletsPerCompartment().intValueExact();
        int compartments = slots / palletsPerCompartment;
        return rack.maxRackLoadKg().min(effectiveBeamPairLoad(rack, rules)
            .multiply(BigDecimal.valueOf(compartments)));
    }

    private static boolean fitsDirectBeam(RackLayout.Pallet pallet, RackLayout.Rack rack,
                                          RackLayout.Clearances rules, BigDecimal bayLength,
                                          int palletCount) {
        return directPalletIssue(pallet, rack, rules, bayLength, palletCount) == null;
    }

    private static String directPalletIssue(RackLayout.Pallet pallet, RackLayout.Rack rack,
                                            RackLayout.Clearances rules, BigDecimal bayLength,
                                            int palletCount) {
        if (rack.beamOuterSpanCm().compareTo(rack.widthCm()) != 0) {
            return "khoảng cách mép ngoài hai dầm phải bằng chiều rộng ngoài của khung là "
                + cm(rack.widthCm()) + ".";
        }
        BigDecimal rackWidth = rack.widthCm();
        BigDecimal maxPalletLength = rackWidth.add(MAX_PALLET_OVERHANG_CM.multiply(TWO));
        if (BigDecimal.valueOf(pallet.lengthCm()).compareTo(rackWidth) < 0) {
            return "chiều dài tấm kê " + pallet.lengthCm() + " cm nhỏ hơn chiều rộng ngoài của khung "
                + rackWidth.stripTrailingZeros().toPlainString() + " cm.";
        }
        if (BigDecimal.valueOf(pallet.lengthCm()).compareTo(maxPalletLength) > 0) {
            return "chiều dài tấm kê " + pallet.lengthCm() + " cm vượt giới hạn "
                + maxPalletLength.stripTrailingZeros().toPlainString() + " cm, tương ứng nhô tối đa "
                + MAX_PALLET_OVERHANG_CM.stripTrailingZeros().toPlainString() + " cm mỗi mặt.";
        }
        BigDecimal maxPalletWidth = bayLength.subtract(rules.palletEndCm().multiply(TWO))
            .subtract(rules.palletGapCm().multiply(BigDecimal.valueOf(palletCount - 1)))
            .divide(BigDecimal.valueOf(palletCount), 6, RoundingMode.DOWN);
        if (BigDecimal.valueOf(pallet.widthCm()).compareTo(maxPalletWidth) > 0) {
            return "chiều rộng tấm kê " + pallet.widthCm() + " cm vượt chiều rộng cho phép "
                + maxPalletWidth.stripTrailingZeros().toPlainString() + " cm trong khoang.";
        }
        return null;
    }

    private static boolean fitsPallets(int along, int across, int count, RackLayout.Clearances rules,
                                       BigDecimal bayLength, BigDecimal bayWidth) {
        return !greater(lengthNeeded(along, count, rules), bayLength) && !greater(widthNeeded(across, rules), bayWidth);
    }

    private static String requiredFootprint(int along, int across, int count, RackLayout.Clearances rules) {
        return cm(lengthNeeded(along, count, rules)) + " × " + cm(widthNeeded(across, rules));
    }

    private static BigDecimal lengthNeeded(int along, int count, RackLayout.Clearances rules) {
        return BigDecimal.valueOf((long) along * count)
            .add(rules.palletGapCm().multiply(BigDecimal.valueOf(count - 1)))
            .add(rules.palletEndCm().multiply(TWO));
    }

    private static BigDecimal widthNeeded(int across, RackLayout.Clearances rules) {
        return BigDecimal.valueOf(across).add(rules.palletSideCm().multiply(TWO));
    }

    private static int boxesPerRack(RackLayout.Sample sample, int perPallet, int palletsPerCompartment,
                                    int compartments, BigDecimal cargoPerCompartmentKg,
                                    BigDecimal cargoPerRackKg) {
        int geometricLimit = perPallet * palletsPerCompartment;
        int weightLimit = underFloorQuota(Integer.MAX_VALUE, cargoPerCompartmentKg, sample);
        int compartmentLimit = Math.min(geometricLimit, weightLimit) * compartments;
        int rackWeightLimit = underFloorQuota(Integer.MAX_VALUE, cargoPerRackKg, sample);
        return Math.min(compartmentLimit, rackWeightLimit);
    }

    private static int underFloorQuota(int geometricCount, BigDecimal quotaKg, RackLayout.Sample sample) {
        return quotaKg.max(BigDecimal.ZERO).divideToIntegralValue(BigDecimal.valueOf(sample.weightKg()))
            .min(BigDecimal.valueOf(geometricCount)).intValueExact();
    }

    private static BigDecimal divide(BigDecimal value, int divisor, boolean preserveGeometryPrecision) {
        return value.divide(BigDecimal.valueOf(divisor), preserveGeometryPrecision ? 12 : 6, RoundingMode.DOWN);
    }

    private static boolean greater(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) > 0;
    }

    private static String cm(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + " cm";
    }

    private static String kg(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',') + " kg";
    }

    private record Footprint(String code, BigDecimal x, BigDecimal y, BigDecimal endX, BigDecimal endY) {
        static Footprint of(RackLayout.Rack rack) {
            boolean rotated = rack.orientationDeg().intValueExact() == 90;
            return new Footprint(rack.code().strip(), rack.xCm(), rack.yCm(),
                rack.xCm().add(rotated ? rack.widthCm() : rack.lengthCm()),
                rack.yCm().add(rotated ? rack.lengthCm() : rack.widthCm()));
        }

        Footprint expandedAcross(BigDecimal amount, boolean acrossGlobalX) {
            return acrossGlobalX
                ? new Footprint(code, x.subtract(amount), y, endX.add(amount), endY)
                : new Footprint(code, x, y.subtract(amount), endX, endY.add(amount));
        }

        boolean overlaps(Footprint other) {
            return greater(endX, other.x) && greater(other.endX, x)
                && greater(endY, other.y) && greater(other.endY, y);
        }
    }
}
