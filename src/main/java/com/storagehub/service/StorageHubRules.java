package com.storagehub.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Các quy định hình học và khoảng hở cố định của StorageHub v8.1.
 *
 * Các giá trị này là quy định nghiệp vụ, không phải trường cho Manager sửa.
 * Kích thước pallet được tính từ số lượng tấm kê và hình học khung thực tế.
 */
public final class StorageHubRules {
    /** Stable label written into new layout snapshots and API responses. */
    public static final String RULES_VERSION = "8.1-demo";
    /** Numeric predecessor retained only for reading old snapshots. */
    public static final int VERSION = 8;

    public static final BigDecimal UPRIGHT_SIZE_CM = bd("8");
    public static final BigDecimal BEAM_HEIGHT_CM = bd("15");
    public static final BigDecimal FIRST_BEAM_BOTTOM_CM = bd("15");
    public static final BigDecimal HANDLING_CLEARANCE_CM = bd("15");
    public static final BigDecimal PALLET_END_CLEARANCE_CM = bd("9");
    public static final BigDecimal PALLET_GAP_CM = bd("10");
    public static final BigDecimal WALL_CLEARANCE_CM = bd("30");
    public static final BigDecimal MAX_PALLET_OVERHANG_CM = bd("5");
    public static final BigDecimal MIN_CARGO_HEIGHT_CM = bd("80");
    public static final BigDecimal MIN_PALLET_WIDTH_CM = bd("80");
    public static final BigDecimal PALLET_HEIGHT_CM = bd("18");
    /** Simulation convention for a lightweight mesh plastic pallet. */
    public static final BigDecimal PALLET_WEIGHT_PER_M2 = bd("9");
    public static final BigDecimal PALLET_MAX_PAYLOAD_KG = bd("500");

    private StorageHubRules() {
    }

    public static BigDecimal calculatePalletWeightKg(BigDecimal lengthCm, BigDecimal widthCm) {
        if (lengthCm == null || widthCm == null
            || lengthCm.signum() <= 0 || widthCm.signum() <= 0) {
            throw new IllegalArgumentException("Kích thước tấm kê phải lớn hơn 0 cm.");
        }
        return lengthCm.multiply(widthCm)
            .movePointLeft(4)
            .multiply(PALLET_WEIGHT_PER_M2)
            .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
