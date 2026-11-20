package com.storagehub.service;

import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.PalletPlan;
import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.repo.StorageUnitRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PalletInventoryService {
    private final StorageUnitRepository units;
    private final RackLayoutService layouts;
    private final ReservationCapacityService capacity;

    record Fit(PalletPlan plan, long fittingUnits, StorageUnit heldUnit, List<String> issues,
               boolean layoutMissing, Integer requiredPallets, Integer availableSlots,
               boolean reviewRequired) {
        Fit(PalletPlan plan, long fittingUnits, StorageUnit heldUnit, List<String> issues,
            boolean layoutMissing) {
            this(plan, fittingUnits, heldUnit, issues, layoutMissing, null, null, false);
        }

        Fit(PalletPlan plan, long fittingUnits, StorageUnit heldUnit, List<String> issues,
            boolean layoutMissing, Integer requiredPallets, Integer availableSlots) {
            this(plan, fittingUnits, heldUnit, issues, layoutMissing, requiredPallets, availableSlots, false);
        }
    }

    record StorageLimits(BigDecimal maxCargoLengthCm, BigDecimal maxCargoWidthCm,
                         BigDecimal maxCargoHeightCm, BigDecimal maxCargoWeightKg) {}

    StorageLimits storageLimits(UnitType type) {
        if (type == null || type.getRackLayoutJson() == null) return null;
        try {
            var layout = layouts.layoutForPacking(type, type.getRackLayoutJson());
            if (layout == null) return null;
            return new StorageLimits(
                layout.maxCargoLengthCm(),
                layout.maxCargoWidthCm(),
                layout.maxCargoHeightCm(),
                layout.unitCargoLimitKg()
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    BigDecimal cargoLimitKg(UnitType type) {
        if (type.getRackLayoutJson() == null) return type.getMaxLoadKg();
        try {
            var layout = layouts.layoutForPacking(type, type.getRackLayoutJson());
            return layout == null ? type.getMaxLoadKg() : layout.unitCargoLimitKg();
        } catch (RuntimeException exception) {
            // The compatibility result will still report the layout issue from
            // the normal packing check. Keep this field usable for the error UI.
            return type.getMaxLoadKg();
        }
    }

    Fit check(
        UnitType type,
        List<GoodsItemRequest> items,
        long availableCount,
        LocalDate startDate,
        LocalDate endDate,
        boolean lock
    ) {
        if (type.getRackLayoutJson() == null) {
            return new Fit(null, 0, null, List.of("Loại gian kho chưa có cấu hình tầng, khoang và pallet."), true);
        }
        var layout = layouts.layoutForPacking(type, type.getRackLayoutJson());
        if (layout == null) {
            return new Fit(null, 0, null, List.of("Loại gian kho chưa có cấu hình tầng, khoang và pallet."), true);
        }
        if (!layout.palletRatingConfigured()) {
            return new Fit(null, 0, null,
                List.of("Cấu hình hình học đã có nhưng chưa xác nhận khối lượng tấm kê rỗng và tải hàng tối đa."),
                false, null, layout.palletSlotCount(), true);
        }
        var result = fit(type, type.getRackLayoutJson(), items);
        if (result.plan() == null) return unavailable(result);

        // Keep the successful packing preview even when no physical unit is
        // currently vacant. The customer UI can then distinguish "fits the
        // layout but no unit is available" from "the goods do not fit".
        if (availableCount == 0) {
            return new Fit(result.plan(), 0, null,
                List.of("Không còn gian kho trống trong kỳ thuê đã chọn."), false,
                result.requiredPallets(), result.availableSlots());
        }

        var heldIds = capacity.livePalletHoldUnitIds(type.getId(), startDate, endDate);
        var candidates = units.findAvailableUnitIds(type.getId()).stream().filter(id -> !heldIds.contains(id)).toList();
        if (lock) {
            for (UUID id : candidates) {
                var unit = units.findByIdForUpdate(id).orElse(null);
                if (unit != null && unit.getStatus() == StorageUnitStatus.available) {
                    var physicalResult = fit(type, layoutJson(unit, type), items);
                    if (physicalResult.plan() != null) {
                        return new Fit(physicalResult.plan(), 1, unit, List.of(), false,
                            physicalResult.requiredPallets(), physicalResult.availableSlots());
                    }
                }
            }
        } else if (!candidates.isEmpty()) {
            return new Fit(result.plan(), Math.min(availableCount, candidates.size()), null, List.of(), false,
                result.requiredPallets(), result.availableSlots());
        }
        return new Fit(result.plan(), 0, null,
            List.of("Các gian thuộc loại này đang được giữ hoặc không còn trống."), false,
            result.requiredPallets(), result.availableSlots());
    }

    public void requireCompatibleAssignment(Reservation reservation, StorageUnit unit) {
        var heldIds = capacity.livePalletHoldUnitIds(unit.getUnitType().getId());
        boolean ownHold = reservation.getPalletHeldUnit() != null
            && reservation.getPalletHeldUnit().getId().equals(unit.getId());
        if (heldIds.contains(unit.getId()) && !ownHold) {
            throw ApiExceptions.conflict("Gian kho đang được giữ cho một đơn đặt kệ pallet khác.");
        }
        if (reservation.getPalletHeldUnit() != null) {
            requireFit(unit, GoodsSnapshotCodec.decode(reservation.getSourceQuote().getGoodsSnapshotJson()));
        }
    }

    List<UUID> assignableIds(Reservation reservation) {
        UnitType type = reservation.getUnitType();
        var heldIds = capacity.livePalletHoldUnitIds(type.getId());
        var declaration = GoodsSnapshotCodec.decode(reservation.getSourceQuote().getGoodsSnapshotJson());
        UUID heldUnitId = reservation.getPalletHeldUnit() == null ? null : reservation.getPalletHeldUnit().getId();
        var candidates = units.findAvailableUnitIds(type.getId()).stream()
            .filter(id -> !heldIds.contains(id) || id.equals(heldUnitId))
            .toList();
        if (type.getRackLayoutJson() != null) {
            return units.findAllById(candidates).stream()
                .filter(unit -> fit(type, layoutJson(unit, type), declaration).plan() != null)
                .map(StorageUnit::getId).toList();
        }
        // Reservations made before the type-level layout can still use the saved per-unit layout.
        return units.findAllById(candidates).stream()
            .filter(unit -> unit.getRackLayoutJson() != null && fit(type, unit.getRackLayoutJson(), declaration).plan() != null)
            .map(StorageUnit::getId).toList();
    }

    void requireFit(StorageUnit unit, List<GoodsItemRequest> items) {
        if (unit == null) throw ApiExceptions.conflict("Gian kho chưa có cấu hình kệ pallet.");
        UnitType type = unit.getUnitType();
        String json = layoutJson(unit, type);
        requireFit(type, json, items);
    }

    void requireFit(UnitType type, List<GoodsItemRequest> items) {
        if (type == null) throw ApiExceptions.conflict("Loại gian chưa có cấu hình kệ pallet.");
        requireFit(type, type.getRackLayoutJson(), items);
    }

    private void requireFit(UnitType type, String json, List<GoodsItemRequest> items) {
        if (json == null) throw ApiExceptions.conflict("Loại gian chưa có cấu hình kệ pallet.");
        var layout = layouts.layoutForPacking(type, json);
        if (layout == null) throw ApiExceptions.conflict("Loại gian chưa có cấu hình kệ pallet.");
        if (!layout.palletRatingConfigured()) {
            throw ApiExceptions.conflict("Cấu hình kệ chưa xác nhận tải trọng tấm kê, chưa thể tiếp nhận hàng.");
        }
        var result = fit(type, json, items);
        if (result.plan() == null) throw ApiExceptions.conflict(result.issue());
    }

    private PalletPacking.Result fit(UnitType type, String json, List<GoodsItemRequest> items) {
        try {
            return PalletPacking.fit(layouts.layoutForPacking(type, json), items);
        } catch (ApiException exception) {
            return new PalletPacking.Result(null, "Cấu hình kệ cần được kiểm tra lại theo thông số loại gian hiện tại.");
        }
    }

    private String layoutJson(StorageUnit unit, UnitType type) {
        return unit != null && unit.getRackLayoutJson() != null
            ? unit.getRackLayoutJson() : type.getRackLayoutJson();
    }

    private Fit unavailable(PalletPacking.Result result) {
        return new Fit(null, 0, null, List.of(result.issue()), false,
            result.requiredPallets(), result.availableSlots());
    }

    private Fit unavailable(String issue) {
        return new Fit(null, 0, null, List.of(issue), false);
    }
}
