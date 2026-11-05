package com.storagehub.validation;

/** Validation rules for Vietnamese CCCD/CMND numbers captured at booking and handover. */
public final class IdentityDocumentPolicy {

    public static final String MESSAGE = "CCCD/CMND phải gồm 9 hoặc 12 chữ số.";

    private IdentityDocumentPolicy() {
    }

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.replaceAll("\\s+", "");
        return normalized.matches("(?:\\d{9}|\\d{12})");
    }
}
