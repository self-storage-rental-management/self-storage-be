package com.storagehub.service;

import com.storagehub.domain.model.User;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.validation.PhoneNumberPolicy;
import java.util.*;

public final class CustomerProfilePolicy {
    private CustomerProfilePolicy() {}
    private static boolean has(String s) { return s != null && !s.isBlank(); }
    private static String phone(String s) {
        if (s == null) return "";
        String value = s.replaceAll("[\\s()+.-]", "");
        return value.startsWith("84") ? "0" + value.substring(2) : value;
    }
    public static void validate(User u, boolean required) {
        var d = u.getProfileDetails();
        if (d != null && has(d.getIdentityDocumentNumber())) {
            String error = com.storagehub.validation.CccdPolicy.error(d.getIdentityDocumentNumber());
            if (error != null) throw ApiExceptions.validation(error, null);
        }
        if (required && (d == null || !has(d.getIdentityDocumentNumber()) || !PhoneNumberPolicy.isValid(u.getPhone()) || !has(u.getPermanentAddress()) || u.getPermanentAddress().trim().length() < 5))
            throw ApiExceptions.conflict("Vui lòng hoàn thiện số điện thoại, địa chỉ, CCCD và người liên hệ khẩn cấp trong hồ sơ trước khi thanh toán cọc hoặc ký hợp đồng.");
        String[] names = {u.getEmergencyContactName(), d == null ? null : d.getSecondaryEmergencyContactName()};
        String[] phones = {u.getEmergencyContactPhone(), d == null ? null : d.getSecondaryEmergencyContactPhone()};
        String[] relations = {d == null ? null : d.getEmergencyContactRelationship(), d == null ? null : d.getSecondaryEmergencyContactRelationship()};
        boolean[] consents = {d != null && d.isEmergencyContactConsent(), d != null && d.isSecondaryEmergencyContactConsent()};
        Set<String> seen = new HashSet<>();
        seen.add(phone(u.getPhone()));
        for (int i = 0; i < 2; i++) {
            boolean present = has(names[i]) || has(phones[i]) || has(relations[i]) || consents[i]
                || (d != null && has(i == 0 ? d.getEmergencyContactEmail() : d.getSecondaryEmergencyContactEmail()));
            if (!present && !(required && i == 0)) continue;
            if (!has(names[i]) || names[i].trim().length() < 2 || !PhoneNumberPolicy.isValid(phones[i]) || !has(relations[i]) || !consents[i])
                throw ApiExceptions.validation("Mỗi người liên hệ cần họ tên, SĐT hợp lệ, quan hệ và xác nhận đã đồng ý cung cấp thông tin.", null);
            if (!seen.add(phone(phones[i])))
                throw ApiExceptions.validation("SĐT người liên hệ phải khác số khách hàng và khác người liên hệ còn lại.", null);
        }
    }
    public record Contact(String name, String phone, String relationship, String email) {}
    public static List<Contact> contacts(User u) {
        var d = u.getProfileDetails();
        var result = new ArrayList<Contact>();
        if (has(u.getEmergencyContactName())) result.add(new Contact(u.getEmergencyContactName(), u.getEmergencyContactPhone(), d == null ? null : d.getEmergencyContactRelationship(), d == null ? null : d.getEmergencyContactEmail()));
        if (d != null && has(d.getSecondaryEmergencyContactName())) result.add(new Contact(d.getSecondaryEmergencyContactName(), d.getSecondaryEmergencyContactPhone(), d.getSecondaryEmergencyContactRelationship(), d.getSecondaryEmergencyContactEmail()));
        return List.copyOf(result);
    }
    public static String snapshot(User u) {
        validate(u, true);
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(contacts(u)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
