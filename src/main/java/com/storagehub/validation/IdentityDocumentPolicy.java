package com.storagehub.validation;

/** Validation rules for identity documents captured at booking, handover and inventory. */
public final class IdentityDocumentPolicy {

    public static final String MESSAGE = "Nhập số CCCD/CMND hoặc số hộ chiếu hợp lệ.";

    private IdentityDocumentPolicy() {
    }

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.replaceAll("\\s+", "");
        return normalized.matches("(?:\\d{9}|\\d{12})")
            || normalized.matches("(?i)(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6,20}");
    }
}
