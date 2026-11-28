package com.storagehub.validation;

import java.time.*;
import java.util.Set;
import java.util.List;

/** Structural checks only. Staff must compare the original document, including the actual date of birth. */
public final class CccdPolicy {
    private CccdPolicy() {}
    private static final Set<String> PROVINCES = loadProvinceCodes();
    private static Set<String> loadProvinceCodes() {
        try (var stream = CccdPolicy.class.getResourceAsStream("/cccd-province-codes.properties")) {
            if (stream == null) throw new IllegalStateException("Missing CCCD birthplace code configuration");
            var properties = new java.util.Properties();
            properties.load(stream);
            return Set.copyOf(List.of(properties.getProperty("codes").trim().split(" +")));
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load CCCD birthplace codes", e); }
    }
    public static String normalize(String raw) { return raw == null ? null : raw.replaceAll("[\\s.\\-]", ""); }
    public static boolean needsBirthdayConfirmation(String raw) {
        String value = normalize(raw);
        if (value == null || !value.matches("[0-9]{12}")) return false;
        int year = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).getYear();
        return year - (1900 + ((value.charAt(3) - '0') / 2) * 100 + Integer.parseInt(value.substring(4, 6))) == 18;
    }
    public static String error(String raw) {
        String value = normalize(raw);
        if (value == null || value.isBlank()) return "Vui lòng nhập CCCD 12 số.";
        if (!value.matches("[0-9]+")) return "CCCD chỉ được chứa chữ số.";
        if (value.length() != 12) return "Vui lòng nhập CCCD 12 số.";
        if (!PROVINCES.contains(value.substring(0, 3))) return "Mã tỉnh/thành trong CCCD không hợp lệ.";
        int birthYear = 1900 + ((value.charAt(3) - '0') / 2) * 100 + Integer.parseInt(value.substring(4, 6));
        int year = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).getYear();
        if (birthYear > year) return "Năm sinh trong CCCD ở tương lai.";
        if (year - birthYear < 18) return "Người thuê phải đủ 18 tuổi.";
        return null;
    }
}
