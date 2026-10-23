package com.storagehub.validation;

import java.util.regex.Pattern;

public final class PhoneNumberPolicy {

    public static final String MESSAGE =
        "Số điện thoại phải bắt đầu bằng 02, 03, 05, 07, 08 hoặc 09; ví dụ 0908123456 hoặc +84908123456.";

    private static final Pattern COMPACT_PHONE = Pattern.compile(
        "^(?:0(?:2\\d{8,9}|[35789]\\d{8})|\\+?84(?:2\\d{8,9}|[35789]\\d{8}))$"
    );

    private PhoneNumberPolicy() {
    }

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return COMPACT_PHONE.matcher(compact(value)).matches();
    }

    public static boolean isOptionalValid(String value) {
        return value == null || value.isBlank() || isValid(value);
    }

    private static String compact(String value) {
        return value.trim().replaceAll("[\\s().-]", "");
    }
}
