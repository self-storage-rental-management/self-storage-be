package com.storagehub.service;

import com.storagehub.api.returns.ReturnInspectionRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed, explainable damage price list used during return inspection.
 * The displayed price is the midpoint of the approved suggested range so
 * Staff cannot enter an arbitrary amount while the customer can still see
 * exactly how the fee was calculated.
 */
public final class ReturnDamagePricing {
    public record Item(String code, String label, String category, String unit,
                       BigDecimal suggestedMin, BigDecimal suggestedMax, BigDecimal unitPrice) {}

    private static final Map<String, Item> CATALOG;

    static {
        Map<String, Item> items = new LinkedHashMap<>();
        add(items, "minor_wall_scratch", "Trầy xước tường", "minor", "điểm/vết", 50_000, 150_000);
        add(items, "minor_paint_peel", "Bong tróc sơn", "minor", "m²", 80_000, 150_000);
        add(items, "minor_hard_stain", "Vết bẩn khó vệ sinh", "minor", "vị trí", 50_000, 100_000);
        add(items, "minor_floor_scratch", "Trầy xước sàn", "minor", "m²", 100_000, 200_000);
        add(items, "minor_metal_dent", "Móp nhẹ vách kim loại", "minor", "vị trí", 100_000, 250_000);
        add(items, "minor_small_accessory", "Hỏng phụ kiện nhỏ", "minor", "cái", 50_000, 200_000);
        add(items, "major_bent_door", "Cửa kho bị cong vênh", "major", "cái", 500_000, 2_000_000);
        add(items, "major_broken_lock", "Khóa cửa bị hỏng", "major", "bộ", 300_000, 1_000_000);
        add(items, "major_cracked_floor", "Sàn kho bị nứt", "major", "m²", 300_000, 800_000);
        add(items, "major_broken_floor", "Sàn kho bị vỡ", "major", "m²", 500_000, 1_500_000);
        add(items, "major_damaged_partition", "Vách ngăn bị thủng", "major", "vị trí", 500_000, 1_500_000);
        add(items, "major_serious_wall_crack", "Tường bị nứt nghiêm trọng", "major", "vị trí", 500_000, 2_000_000);
        add(items, "major_electrical_damage", "Hệ thống điện bị hư hỏng", "major", "lần", 500_000, 3_000_000);
        add(items, "major_unusable_door", "Cửa kho không thể sử dụng", "major", "cái", 1_500_000, 4_000_000);
        CATALOG = Collections.unmodifiableMap(items);
    }

    private ReturnDamagePricing() {}

    private static void add(Map<String, Item> items, String code, String label, String category,
                            String unit, int min, int max) {
        BigDecimal low = BigDecimal.valueOf(min);
        BigDecimal high = BigDecimal.valueOf(max);
        BigDecimal midpoint = low.add(high).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);
        items.put(code, new Item(code, label, category, unit, low, high, midpoint));
    }

    public static Map<String, Item> catalog() {
        return CATALOG;
    }

    public static BigDecimal calculate(List<ReturnInspectionRequest.DamageItem> damageItems) {
        BigDecimal total = BigDecimal.ZERO;
        if (damageItems == null) return total.setScale(0);
        for (ReturnInspectionRequest.DamageItem item : damageItems) {
            if (item == null || item.code() == null || item.code().isBlank()) {
                throw new IllegalArgumentException("Mã hạng mục hư hỏng không hợp lệ.");
            }
            Item price = CATALOG.get(item.code());
            if (price == null) throw new IllegalArgumentException("Hạng mục hư hỏng không nằm trong bảng giá.");
            BigDecimal quantity = item.quantity();
            if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(BigDecimal.valueOf(100_000)) > 0) {
                throw new IllegalArgumentException("Số lượng hư hỏng phải lớn hơn 0 và không vượt quá 100.000.");
            }
            total = total.add(price.unitPrice().multiply(quantity));
        }
        return total.setScale(0, RoundingMode.HALF_UP);
    }

    public static String describe(List<ReturnInspectionRequest.DamageItem> damageItems) {
        if (damageItems == null || damageItems.isEmpty()) return "";
        return damageItems.stream().map(item -> {
            Item price = CATALOG.get(item.code());
            return price == null ? item.code() : price.label() + " x " + item.quantity().stripTrailingZeros().toPlainString()
                + " " + price.unit() + " (" + price.unitPrice().toPlainString() + " đ/" + price.unit() + ")";
        }).reduce((left, right) -> left + "; " + right).orElse("");
    }
}
