package com.storagehub.validation;

/** CCCD structural validation shared by booking and handover requests. */
public final class IdentityDocumentPolicy {
    public static final String MESSAGE = "Vui lòng nhập CCCD 12 số, có mã nơi sinh và năm sinh hợp lệ; người thuê phải đủ 18 tuổi.";
    private IdentityDocumentPolicy() {}
    public static boolean isValid(String value) { return CccdPolicy.error(value) == null; }
}
