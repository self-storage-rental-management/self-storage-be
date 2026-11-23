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
