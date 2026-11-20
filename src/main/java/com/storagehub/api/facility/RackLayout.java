package com.storagehub.api.facility;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.storagehub.api.reservation.PalletPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RackLayout {
    private RackLayout() {}

    public record Configuration(
        @NotNull @Valid Clearances clearances,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Rack> racks,
        @Valid Pallet pallet,
        Boolean autoGeneratePallet,
        @Valid List<@NotNull @Valid PalletRating> palletRatings,
        @Min(1) Integer requestedRackCount,
        @Size(max = 40) String layoutMode
    ) {
        public Configuration {
            // Auto mode ignores the legacy pallet/rating input and generates
            // the approved pallet from rack geometry and pallet count.
            // Legacy configurations still get the old default pallet for
            // backwards compatibility.
            if (pallet == null && !Boolean.TRUE.equals(autoGeneratePallet)) pallet = Pallet.DEFAULT;
            if (pallet != null && !Boolean.TRUE.equals(autoGeneratePallet) && pallet.maxPayloadKg() == null) {
                pallet = new Pallet(pallet.lengthCm(), pallet.widthCm(), pallet.heightCm(), pallet.emptyKg(),
                    BigDecimal.valueOf(1500));
            }
            if (autoGeneratePallet == null) autoGeneratePallet = false;
            if (palletRatings == null) palletRatings = List.of();
            if (requestedRackCount == null) requestedRackCount = racks == null ? 0 : racks.size();
            if (layoutMode == null || layoutMode.isBlank()) layoutMode = "MANUAL";
        }

        public Configuration(Clearances clearances, List<Rack> racks) {
            this(clearances, racks, Pallet.DEFAULT, false, List.of(), null, null);
        }

        public Configuration(Clearances clearances, List<Rack> racks, Pallet pallet) {
            this(clearances, racks, pallet, false, List.of(), null, null);
        }

        public Configuration(Clearances clearances, List<Rack> racks, Pallet pallet,
                             boolean autoGeneratePallet) {
            this(clearances, racks, pallet, Boolean.valueOf(autoGeneratePallet), List.of(), null, null);
        }

        public Configuration(Clearances clearances, List<Rack> racks, Pallet pallet,
                             boolean autoGeneratePallet, List<PalletRating> palletRatings) {
            this(clearances, racks, pallet, Boolean.valueOf(autoGeneratePallet), palletRatings, null, null);
        }

    }

    public record Pallet(
        @NotNull @Positive Integer lengthCm,
        @NotNull @Positive Integer widthCm,
        @NotNull @Positive @Digits(integer = 4, fraction = 2) BigDecimal heightCm,
        // These values are output of the approved pallet specification in
        // auto mode. They are retained in the DTO for saved-layout snapshots.
        @Digits(integer = 6, fraction = 2) BigDecimal emptyKg,
        @Digits(integer = 7, fraction = 2) BigDecimal maxPayloadKg
    ) {
        private static final Pallet DEFAULT = new Pallet(120, 80, new BigDecimal("14.4"), BigDecimal.valueOf(25), BigDecimal.valueOf(1500));

        public Pallet(Integer lengthCm, Integer widthCm, BigDecimal heightCm, BigDecimal emptyKg) {
            this(lengthCm, widthCm, heightCm, emptyKg, BigDecimal.valueOf(1500));
        }
    }

    /** Dữ liệu rating cũ, chỉ giữ để đọc snapshot trước đây. */
    public record PalletRating(
        @NotNull @Positive Integer lengthCm,
        @NotNull @Positive Integer widthCm,
        @NotNull @Positive @Digits(integer = 4, fraction = 2) BigDecimal heightCm,
        @Digits(integer = 6, fraction = 2) BigDecimal emptyKg,
        @Digits(integer = 7, fraction = 2) BigDecimal maxPayloadKg
    ) {
        public boolean isConfigured() {
            return emptyKg != null && emptyKg.signum() > 0
                && maxPayloadKg != null && maxPayloadKg.signum() > 0;
        }

        public boolean matches(Pallet pallet) {
            return pallet != null && lengthCm.equals(pallet.lengthCm())
                && widthCm.equals(pallet.widthCm())
                && heightCm.compareTo(pallet.heightCm()) == 0;
        }

        public Pallet toPallet() {
            return new Pallet(lengthCm, widthCm, heightCm, emptyKg, maxPayloadKg);
        }
    }

    public record Clearances(
        @NotNull @PositiveOrZero BigDecimal palletGapCm,
        @NotNull @PositiveOrZero BigDecimal palletEndCm,
        @NotNull @PositiveOrZero BigDecimal palletSideCm,
        @NotNull @PositiveOrZero BigDecimal handlingCm,
        @NotNull @PositiveOrZero BigDecimal maxPalletOverhangCm,
        @NotNull @PositiveOrZero BigDecimal rackWallClearanceCm
    ) {
        public Clearances {
            // The two old side rules remain available for legacy layouts. A
            // positive overhang switches the calculation to direct beam support.
            if (palletGapCm == null) palletGapCm = BigDecimal.ZERO;
            if (palletEndCm == null) palletEndCm = BigDecimal.ZERO;
            if (palletSideCm == null) palletSideCm = BigDecimal.ZERO;
            if (handlingCm == null) handlingCm = BigDecimal.ZERO;
            if (maxPalletOverhangCm == null) maxPalletOverhangCm = BigDecimal.ZERO;
            if (rackWallClearanceCm == null) rackWallClearanceCm = BigDecimal.ZERO;
        }

        public Clearances(BigDecimal palletGapCm, BigDecimal palletEndCm,
                          BigDecimal palletSideCm, BigDecimal handlingCm) {
            this(palletGapCm, palletEndCm, palletSideCm, handlingCm, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public boolean directBeamSupport() {
            return maxPalletOverhangCm.signum() > 0;
        }
    }

    public record Rack(
        @NotBlank @Size(max = 30) String code,
        @NotNull @Positive BigDecimal lengthCm,
        @NotNull @Positive BigDecimal widthCm,
        @NotNull @Positive BigDecimal heightCm,
        @NotNull @PositiveOrZero BigDecimal xCm,
        @NotNull @PositiveOrZero BigDecimal yCm,
        @NotNull @Digits(integer = 2, fraction = 0) BigDecimal orientationDeg,
        @NotNull @Min(2) @Max(20) @Digits(integer = 2, fraction = 0) BigDecimal uprightFrameCount,
        @NotNull @Positive BigDecimal uprightSizeCm,
        @NotNull @Min(1) @Digits(integer = 2, fraction = 0) BigDecimal levelCount,
        @NotNull @Positive BigDecimal beamHeightCm,
        @NotNull @PositiveOrZero BigDecimal firstBeamBottomCm,
        @NotNull @Min(1) @Max(20) @Digits(integer = 2, fraction = 0) BigDecimal palletsPerCompartment,
        /** Legacy field: read for old snapshots, never used by v8.1 calculations. */
        @Positive BigDecimal maxLoadedPalletKg,
        @NotNull @Positive BigDecimal maxRackLoadKg,
        @NotNull @Positive BigDecimal beamOuterSpanCm,
        /** v8.1 structural input: the load limit for one beam pair/compartment. */
        @Positive BigDecimal maxBeamPairLoadKg
    ) {
        public Rack {
            if (beamOuterSpanCm == null) beamOuterSpanCm = widthCm;
        }

        public Rack(String code, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                    BigDecimal xCm, BigDecimal yCm, BigDecimal orientationDeg,
                    BigDecimal uprightFrameCount, BigDecimal uprightSizeCm, BigDecimal levelCount,
                    BigDecimal beamHeightCm, BigDecimal firstBeamBottomCm,
                    BigDecimal palletsPerCompartment, BigDecimal maxLoadedPalletKg,
                    BigDecimal maxRackLoadKg) {
            this(code, lengthCm, widthCm, heightCm, xCm, yCm, orientationDeg, uprightFrameCount,
                uprightSizeCm, levelCount, beamHeightCm, firstBeamBottomCm, palletsPerCompartment,
                maxLoadedPalletKg, maxRackLoadKg, widthCm, maxLoadedPalletKg);
        }
    }

    public record Save(
        @NotNull @Valid Configuration configuration,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String previewKey
    ) {}

    /** Structural values entered by the Manager. Layout rules and generated
     * coordinates remain owned by the Backend. */
    public record RackSpecification(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lengthCm,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal widthCm,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal heightCm,
        @NotNull @Min(2) @Max(20) Integer uprightFrameCount,
        @NotNull @Min(1) Integer levelCount,
        @NotNull @Min(1) @Max(20) Integer palletsPerCompartment,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal maxRackLoadKg,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal maxBeamPairLoadKg
    ) {}

    public record Draft(
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal lengthM,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal widthM,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal heightM,
        /** New v8.1 boundary aliases. Values are centimetres and are mapped
         * to the existing metre-based UnitType model in the compact ctor. */
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lengthCm,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal widthCm,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal heightCm,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal maxLoadKg,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal maxStoredLoadKg,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal aisleWidthM,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal storageAisleWidthM,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateWidthM,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateHeightM,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lowestObstructionHeightM,
        @DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal overheadClearanceM,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal storageAisleWidthCm,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateWidthCm,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateHeightCm,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lowestObstructionHeightCm,
        @DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal overheadClearanceCm,
        @Min(1) Integer rackCount,
        @Valid Configuration configuration,
        @Valid RackSpecification rackSpecification
    ) {
        public Draft {
            if (lengthM == null && lengthCm != null) lengthM = lengthCm.movePointLeft(2);
            if (widthM == null && widthCm != null) widthM = widthCm.movePointLeft(2);
            if (heightM == null && heightCm != null) heightM = heightCm.movePointLeft(2);
            if (lengthCm == null && lengthM != null) lengthCm = lengthM.movePointRight(2);
            if (widthCm == null && widthM != null) widthCm = widthM.movePointRight(2);
            if (heightCm == null && heightM != null) heightCm = heightM.movePointRight(2);
            if (storageAisleWidthM == null && storageAisleWidthCm != null) storageAisleWidthM = storageAisleWidthCm.movePointLeft(2);
            if (gateWidthM == null && gateWidthCm != null) gateWidthM = gateWidthCm.movePointLeft(2);
            if (gateHeightM == null && gateHeightCm != null) gateHeightM = gateHeightCm.movePointLeft(2);
            if (lowestObstructionHeightM == null && lowestObstructionHeightCm != null) lowestObstructionHeightM = lowestObstructionHeightCm.movePointLeft(2);
            if (overheadClearanceM == null && overheadClearanceCm != null) overheadClearanceM = overheadClearanceCm.movePointLeft(2);
            if (storageAisleWidthCm == null && storageAisleWidthM != null) storageAisleWidthCm = storageAisleWidthM.movePointRight(2);
            if (gateWidthCm == null && gateWidthM != null) gateWidthCm = gateWidthM.movePointRight(2);
            if (gateHeightCm == null && gateHeightM != null) gateHeightCm = gateHeightM.movePointRight(2);
            if (lowestObstructionHeightCm == null && lowestObstructionHeightM != null) lowestObstructionHeightCm = lowestObstructionHeightM.movePointRight(2);
            if (overheadClearanceCm == null && overheadClearanceM != null) overheadClearanceCm = overheadClearanceM.movePointRight(2);
        }

        /** Backwards-compatible constructor for callers that do not set a gross load limit. */
        public Draft(BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM,
                     BigDecimal maxLoadKg, Configuration configuration) {
            this(lengthM, widthM, heightM,
                null, null, null,
                maxLoadKg, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, configuration, null);
        }

        /** Backwards-compatible constructor for callers that provide only the gross load limit. */
        public Draft(BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM,
                     BigDecimal maxLoadKg, BigDecimal maxStoredLoadKg,
                     Configuration configuration) {
            this(lengthM, widthM, heightM,
                null, null, null,
                maxLoadKg, maxStoredLoadKg,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, configuration, null);
        }

        /** Constructor for the auto-layout endpoint: only the warehouse
         * measurements, limits and requested rack count are needed. */
        public Draft(BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM,
                     BigDecimal maxLoadKg, BigDecimal maxStoredLoadKg, Integer rackCount) {
            this(lengthM, widthM, heightM,
                null, null, null,
                maxLoadKg, maxStoredLoadKg,
                null, null, null, null, null, null,
                null, null, null, null, null,
                rackCount, null, null);
        }
    }

    public record Sample(String name, int lengthCm, int widthCm, int heightCm, int weightKg, int maxLayers) {}

    public record Level(int number, BigDecimal supportTopCm, BigDecimal clearHeightCm,
                        BigDecimal maxCargoHeightCm) {
        public Level(int number, BigDecimal supportTopCm, BigDecimal clearHeightCm) {
            this(number, supportTopCm, clearHeightCm, null);
        }
    }

    public record RackPreview(
        String code, int bayCount, List<Level> levels, int compartmentCount, int palletSlotCount,
        BigDecimal bayLengthCm, BigDecimal bayWidthCm, BigDecimal maxCargoHeightCm,
        int palletAlongRackCm, int palletAcrossRackCm, int smallBoxesPerPallet, int largeBoxesPerPallet,
        int smallBoxesPerRack, int largeBoxesPerRack, BigDecimal effectiveRackLoadKg,
        BigDecimal maxBeamPairLoadKg, int maxPalletCountByGeometry,
        BigDecimal actualPalletOverhangCm, String geometryStatus, String operationStatus
    ) {
        public RackPreview(String code, int bayCount, List<Level> levels, int compartmentCount, int palletSlotCount,
                           BigDecimal bayLengthCm, BigDecimal bayWidthCm, BigDecimal maxCargoHeightCm,
                           int palletAlongRackCm, int palletAcrossRackCm, int smallBoxesPerPallet,
                           int largeBoxesPerPallet, int smallBoxesPerRack, int largeBoxesPerRack) {
            this(code, bayCount, levels, compartmentCount, palletSlotCount, bayLengthCm, bayWidthCm,
                maxCargoHeightCm, palletAlongRackCm, palletAcrossRackCm, smallBoxesPerPallet,
                largeBoxesPerPallet, smallBoxesPerRack, largeBoxesPerRack, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, "LEGACY", "REVIEW_REQUIRED");
        }

        public RackPreview(String code, int bayCount, List<Level> levels, int compartmentCount, int palletSlotCount,
                           BigDecimal bayLengthCm, BigDecimal bayWidthCm, BigDecimal maxCargoHeightCm,
                           int palletAlongRackCm, int palletAcrossRackCm, int smallBoxesPerPallet,
                           int largeBoxesPerPallet, int smallBoxesPerRack, int largeBoxesPerRack,
                           BigDecimal effectiveRackLoadKg, BigDecimal maxBeamPairLoadKg) {
            this(code, bayCount, levels, compartmentCount, palletSlotCount, bayLengthCm, bayWidthCm,
                maxCargoHeightCm, palletAlongRackCm, palletAcrossRackCm, smallBoxesPerPallet,
                largeBoxesPerPallet, smallBoxesPerRack, largeBoxesPerRack, effectiveRackLoadKg,
                maxBeamPairLoadKg, 0, BigDecimal.ZERO, "LEGACY", "REVIEW_REQUIRED");
        }
    }

    public record Result(
        Configuration configuration, String rulesVersion, List<RackPreview> racks, int palletSlotCount,
        int smallBoxesPerUnit, int largeBoxesPerUnit, Sample smallBox, Sample largeBox,
        BigDecimal warehouseAreaM2, BigDecimal unitCargoLimitKg, BigDecimal minCargoHeightCm,
        BigDecimal palletHeightCm, BigDecimal palletEmptyKg, BigDecimal palletMaxPayloadKg,
        int rackCount, int levelsPerRack, int maxLevelsByHeight, int compartmentsPerLevel, int totalCompartments, int totalPallets,
        BigDecimal compartmentClearLengthCm, BigDecimal compartmentClearWidthCm, BigDecimal compartmentClearHeightCm,
        BigDecimal palletLengthCm, BigDecimal palletWidthCm, BigDecimal palletEmptyWeightKg,
        BigDecimal totalPalletEmptyWeightKg,
        BigDecimal maxPalletPayloadKg, BigDecimal maxBeamPairLoadKg, BigDecimal maxRackLoadKg,
        BigDecimal effectiveRackLoadKg, BigDecimal maxCargoLengthCm, BigDecimal maxCargoWidthCm,
        BigDecimal maxCargoHeightCm, BigDecimal maxCargoWeightPerCompartmentKg,
        BigDecimal maxCargoWeightPerRackKg, BigDecimal maxCargoWeightPerWarehouseKg,
        BigDecimal maxStoredLoadAtCapacityKg, Integer maxPalletCountByLoad,
        int maxPalletCountByGeometry, BigDecimal actualPalletOverhangCm,
        Integer maxFeasiblePalletCount, List<PalletRatingOption> palletRatingOptions,
        String geometryStatus, String operationStatus, boolean palletRatingConfigured,
        String availabilityStatus, List<String> warnings,
        @JsonInclude(JsonInclude.Include.NON_NULL) PalletPlan smallBoxPlan,
        @JsonInclude(JsonInclude.Include.NON_NULL) PalletPlan largeBoxPlan,
        @JsonInclude(JsonInclude.Include.NON_NULL) MixedBoxExample mixedBoxExample
    ) {
        public Result {
            if (racks == null) racks = List.of();
            if (palletRatingOptions == null) palletRatingOptions = List.of();
            if (availabilityStatus == null || availabilityStatus.isBlank()) availabilityStatus = "NOT_EVALUATED";
            if (warnings == null) warnings = List.of();
            else warnings = List.copyOf(warnings);
        }

        public Result withSamplePlans(PalletPlan small, PalletPlan large, MixedBoxExample mixed) {
            return new Result(configuration, rulesVersion, racks, palletSlotCount, smallBoxesPerUnit, largeBoxesPerUnit,
                smallBox, largeBox, warehouseAreaM2, unitCargoLimitKg, minCargoHeightCm, palletHeightCm, palletEmptyKg,
                palletMaxPayloadKg, rackCount, levelsPerRack, maxLevelsByHeight, compartmentsPerLevel, totalCompartments, totalPallets,
                compartmentClearLengthCm, compartmentClearWidthCm, compartmentClearHeightCm, palletLengthCm,
                palletWidthCm, palletEmptyWeightKg, totalPalletEmptyWeightKg, maxPalletPayloadKg, maxBeamPairLoadKg, maxRackLoadKg,
                effectiveRackLoadKg, maxCargoLengthCm, maxCargoWidthCm, maxCargoHeightCm,
                maxCargoWeightPerCompartmentKg, maxCargoWeightPerRackKg, maxCargoWeightPerWarehouseKg,
                maxStoredLoadAtCapacityKg, maxPalletCountByLoad,
                maxPalletCountByGeometry, actualPalletOverhangCm, maxFeasiblePalletCount, palletRatingOptions,
                geometryStatus, operationStatus,
                palletRatingConfigured, availabilityStatus, warnings, small, large, mixed);
        }

        public Result withAvailabilityStatus(String status) {
            return new Result(configuration, rulesVersion, racks, palletSlotCount, smallBoxesPerUnit, largeBoxesPerUnit,
                smallBox, largeBox, warehouseAreaM2, unitCargoLimitKg, minCargoHeightCm, palletHeightCm, palletEmptyKg,
                palletMaxPayloadKg, rackCount, levelsPerRack, maxLevelsByHeight, compartmentsPerLevel, totalCompartments, totalPallets,
                compartmentClearLengthCm, compartmentClearWidthCm, compartmentClearHeightCm, palletLengthCm,
                palletWidthCm, palletEmptyWeightKg, totalPalletEmptyWeightKg, maxPalletPayloadKg, maxBeamPairLoadKg, maxRackLoadKg,
                effectiveRackLoadKg, maxCargoLengthCm, maxCargoWidthCm, maxCargoHeightCm,
                maxCargoWeightPerCompartmentKg, maxCargoWeightPerRackKg, maxCargoWeightPerWarehouseKg,
                maxStoredLoadAtCapacityKg, maxPalletCountByLoad,
                maxPalletCountByGeometry, actualPalletOverhangCm, maxFeasiblePalletCount, palletRatingOptions,
                geometryStatus, operationStatus, palletRatingConfigured, status, warnings,
                smallBoxPlan, largeBoxPlan, mixedBoxExample);
        }
    }

    public record PalletRatingOption(
        int palletsPerCompartment, int lengthCm, int widthCm, int heightCm,
        boolean ratingConfigured, boolean feasible, BigDecimal emptyKg,
        BigDecimal maxPayloadKg, String reason
    ) {}

    public record MixedBoxExample(int smallBoxQuantity, int largeBoxQuantity, PalletPlan plan, String issue) {}

    public record Preview(String previewKey, Result result) {}

    public record Saved(Result result, Instant savedAt) {}

    public record TypeSummary(UUID id, String code, String name, String facilityName) {}
}
