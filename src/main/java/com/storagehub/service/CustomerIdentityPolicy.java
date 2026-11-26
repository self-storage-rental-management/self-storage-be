package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.validation.CccdPolicy;
import com.storagehub.common.api.ApiExceptions;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class CustomerIdentityPolicy {
    private CustomerIdentityPolicy() {}
    static void requireMinorCorrection(String saved, String actual) {
        if (correctionDistance(saved, actual) <= 1) return;
        throw ApiExceptions.validation("Chỉ được sửa tối đa một chữ số CCCD sau khi đã xác minh.", null);
    }

    static final int HANDOVER_CORRECTION_LIMIT = 3;

    static int correctionDistance(String saved, String actual) {
        String previous = CccdPolicy.normalize(saved);
        if (previous == null || previous.isBlank() || previous.equals(actual)) return 0;
        if (previous.matches("[0-9]{12}") && actual != null && actual.matches("[0-9]{12}")) {
            int differences = 0;
            for (int i = 0; i < 12; i++) {
                if (previous.charAt(i) != actual.charAt(i)) differences++;
            }
            return differences;
        }
        return Integer.MAX_VALUE;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void assign(User user, String raw, UserRepository users) {
        String value = CccdPolicy.normalize(raw);
        if (value != null && !value.isBlank()) {
            String error = CccdPolicy.error(value);
            if (error != null) throw ApiExceptions.validation(error, null);
            if (users.existsByIdentityDocumentHashAndIdNot(hash(value), user.getId()))
                throw ApiExceptions.conflict("CCCD đã được sử dụng bởi tài khoản khác.");
        }
        if (user.getProfileDetails() == null) user.setProfileDetails(new CustomerProfileDetails());
        user.getProfileDetails().setIdentityDocumentNumber(value == null || value.isBlank() ? null : value);
        user.setIdentityDocumentHash(value == null || value.isBlank() ? null : hash(value));
    }
}
