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
        String previous = CccdPolicy.normalize(saved);
        String corrected = CccdPolicy.normalize(actual);
        if (previous != null && previous.matches("[0-9]{12}") && corrected != null
            && corrected.matches("[0-9]{12}") && !previous.substring(0, 6).equals(corrected.substring(0, 6))) {
            throw ApiExceptions.validation("Không được sửa 6 số đầu CCCD gồm mã tỉnh, giới tính/thế kỷ và năm sinh.", null);
        }
        if (correctionDistance(saved, corrected) <= HANDOVER_CORRECTION_LIMIT) return;
        throw ApiExceptions.validation("Chỉ được sửa tối đa 3 chữ số trong 6 số cuối CCCD.", null);
    }

    static final int HANDOVER_CORRECTION_LIMIT = 3;

    static int correctionDistance(String saved, String actual) {
        String previous = CccdPolicy.normalize(saved);
        if (previous == null || previous.isBlank() || previous.equals(actual)) return 0;
        if (previous.matches("[0-9]{12}") && actual != null && actual.matches("[0-9]{12}")) {
            if (!previous.substring(0, 6).equals(actual.substring(0, 6))) return Integer.MAX_VALUE;
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
