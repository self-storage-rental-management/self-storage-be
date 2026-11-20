package com.storagehub.api.reservation;

import com.storagehub.api.facility.RackLayout;
import java.math.BigDecimal;
import java.util.List;

public record PalletPlan(int palletLengthCm, int palletWidthCm, BigDecimal palletHeightCm,
                         BigDecimal emptyPalletKg, BigDecimal palletMaxPayloadKg,
                         int requiredPallets, int availableSlots,
                         BigDecimal totalLoadedWeightKg, List<Item> items, List<Rack> racks,
                         List<Pallet> pallets) {
    public PalletPlan(int palletLengthCm, int palletWidthCm, BigDecimal palletHeightCm,
                      BigDecimal emptyPalletKg, int requiredPallets, int availableSlots,
                      BigDecimal totalLoadedWeightKg, List<Item> items, List<Rack> racks) {
        this(palletLengthCm, palletWidthCm, palletHeightCm, emptyPalletKg, null, requiredPallets, availableSlots,
            totalLoadedWeightKg, items, racks, List.of());
    }

    public record Item(int itemNumber, int quantity, int requiredPallets, int maxPiecesOnOnePallet,
                       int allowedStackLayers, BigDecimal weightPerItemKg, List<Allocation> allocations) {}
    public record Allocation(String rackCode, int compartmentNumber, int palletCount,
                             BigDecimal compartmentCargoLimitKg, int piecesPerPallet,
                             BigDecimal loadedWeightKg, Capacity capacity) {}
    public record Capacity(BigDecimal maxLoadedKg, BigDecimal cargoWeightLimitKg, int piecesPerLayer,
                           int usableLayers, int maxPiecesByDimensions, int maxPiecesByWeight,
                           int maxPieces, BigDecimal placementLengthCm,
                           BigDecimal placementWidthCm, BigDecimal placementHeightCm) {}

    /** Một tấm kê thực tế trong phương án, có thể chứa nhiều dòng hàng. */
    public record Pallet(int palletNumber, String rackCode, int compartmentNumber,
                         BigDecimal compartmentCargoLimitKg, BigDecimal cargoWeightKg,
                         BigDecimal loadedWeightKg, List<ItemAllocation> items,
                         List<BoxPlacement> placements) {}

    public record ItemAllocation(int itemNumber, int quantity, int layers,
                                 BigDecimal cargoWeightKg) {}

    /** Vị trí ba chiều của một kiện trên tấm kê. Đơn vị là cm. */
    public record BoxPlacement(int itemNumber, int boxNumber, int layer,
                               BigDecimal xCm, BigDecimal yCm, BigDecimal zCm,
                               BigDecimal lengthCm, BigDecimal widthCm,
                               BigDecimal heightCm) {}
    public record Rack(String code, int usedSlots, int totalSlots, BigDecimal loadedWeightKg,
                       BigDecimal maxLoadKg, BigDecimal slotMaxLoadKg, int compartments,
                       BigDecimal bayLengthCm, BigDecimal bayWidthCm, BigDecimal maxCargoHeightCm,
                       List<RackLayout.Level> levels) {}
}
