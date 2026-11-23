package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import java.util.*;

final class CustomerProfileDetailsPolicy {
    private CustomerProfileDetailsPolicy() {}
    static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> input) {
        var result = new LinkedHashMap<String, Object>(current == null ? Map.of() : current);
        var textKeys = Set.of("identityDocumentNumber", "emergencyContactRelationship", "emergencyContactEmail",
            "secondaryEmergencyContactName", "secondaryEmergencyContactPhone", "secondaryEmergencyContactRelationship", "secondaryEmergencyContactEmail");
        var booleanKeys = Set.of("emergencyContactConsent", "secondaryEmergencyContactConsent");
        for (var entry : input.entrySet()) {
            String key = entry.getKey();
            // Verification is server-owned. A customer cannot set or clear it.
            if (key.equals("identityVerifiedAt")) continue;
            Object value = entry.getValue();
            if (textKeys.contains(key)) {
                if (value != null && !(value instanceof String)) throw ApiExceptions.validation("Thông tin hồ sơ không đúng định dạng.", null);
                String text = value == null ? "" : ((String)value).trim();
                if (text.length() > 320) throw ApiExceptions.validation("Thông tin hồ sơ vượt quá độ dài cho phép.", null);
                if (key.equals("identityDocumentNumber")) {
                    text = text.replaceAll("\\s+", "");
                    if (!text.isEmpty() && !text.matches("\\d{12}")) throw ApiExceptions.validation("CCCD phải gồm 12 chữ số.", null);
                    if (result.get("identityVerifiedAt") != null && !Objects.equals(result.get(key), text))
                        throw ApiExceptions.conflict("CCCD đã được xác minh. Vui lòng liên hệ cơ sở để điều chỉnh.");
                }
                if (key.endsWith("Email") && !text.isEmpty() && !text.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                    throw ApiExceptions.validation("Email người liên hệ không hợp lệ.", null);
                if (key.equals("secondaryEmergencyContactPhone") && !com.storagehub.validation.PhoneNumberPolicy.isOptionalValid(text))
                    throw ApiExceptions.validation("Số điện thoại người liên hệ không hợp lệ.", null);
                result.put(key, text);
            } else if (booleanKeys.contains(key)) {
                if (!(value instanceof Boolean)) throw ApiExceptions.validation("Xác nhận đồng ý không đúng định dạng.", null);
                result.put(key, value);
            } else throw ApiExceptions.validation("Trường thông tin hồ sơ không được hỗ trợ.", null);
        }
        return result;
    }
}
