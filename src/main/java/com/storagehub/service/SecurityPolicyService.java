package com.storagehub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.repo.SystemSettingRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reads security controls from system_settings at the point they are used. */
@Service
@RequiredArgsConstructor
public class SecurityPolicyService {

    private static final int DEFAULT_LOCKOUT_ATTEMPTS = 5;
    private static final long DEFAULT_SESSION_TIMEOUT_SECONDS = 1800;
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

    private final SystemSettingRepository repository;
    private final ObjectMapper objectMapper;

    public int lockoutAttempts() {
        String value = textValue("lockoutAttempts");
        if (value == null) return DEFAULT_LOCKOUT_ATTEMPTS;
        Matcher matcher = FIRST_NUMBER.matcher(value);
        if (!matcher.find()) return DEFAULT_LOCKOUT_ATTEMPTS;
        try {
            return Math.max(1, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LOCKOUT_ATTEMPTS;
        }
    }

    public long sessionTimeoutSeconds() {
        String value = textValue("sessionTimeout");
        if (value == null) return DEFAULT_SESSION_TIMEOUT_SECONDS;
        Matcher matcher = FIRST_NUMBER.matcher(value);
        if (!matcher.find()) return DEFAULT_SESSION_TIMEOUT_SECONDS;
        try {
            long amount = Math.max(1, Long.parseLong(matcher.group(1)));
            return value.toLowerCase(java.util.Locale.ROOT).contains("hour")
                ? amount * 3600L : amount * 60L;
        } catch (NumberFormatException ignored) {
            return DEFAULT_SESSION_TIMEOUT_SECONDS;
        }
    }

    public boolean requireTwoFactor() {
        return repository.findBySettingKey("require2FA")
            .map(setting -> {
                try {
                    JsonNode value = objectMapper.readTree(setting.getValue());
                    return value != null && value.isBoolean() && value.booleanValue();
                } catch (Exception ignored) {
                    return false;
                }
            })
            .orElse(false);
    }

    private String textValue(String key) {
        return repository.findBySettingKey(key).map(setting -> {
            try {
                JsonNode value = objectMapper.readTree(setting.getValue());
                return value != null && value.isTextual() ? value.textValue() : null;
            } catch (Exception ignored) {
                return null;
            }
        }).orElse(null);
    }
}
