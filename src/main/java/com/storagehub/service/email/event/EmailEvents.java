package com.storagehub.service.email.event;

import java.util.UUID;

public final class EmailEvents {

    private EmailEvents() {
    }

    public record SendVerifyEmailEvent(
        UUID userId,
        String toEmail,
        String fullName,
        String verifyUrl,
        int expiryMinutes
    ) {
    }

    public record SendPasswordResetEvent(
        UUID userId,
        String toEmail,
        String fullName,
        String resetUrl,
        int expiryMinutes,
        String otp
    ) {
        public SendPasswordResetEvent(
            UUID userId,
            String toEmail,
            String fullName,
            String resetUrl,
            int expiryMinutes
        ) {
            this(userId, toEmail, fullName, resetUrl, expiryMinutes, null);
        }
    }

    public record SendNewLoginEvent(
        UUID userId,
        String toEmail,
        String fullName,
        String loginTime,
        String device,
        String browser,
        String location,
        String ipAddress,
        String securityUrl
    ) {
    }

    public record SendAccountCreatedEvent(
        UUID userId,
        String toEmail,
        String fullName,
        String createdAt,
        String tempPassword,
        String loginUrl,
        boolean isReset
    ) {
        public SendAccountCreatedEvent(
            UUID userId,
            String toEmail,
            String fullName,
            String createdAt,
            String tempPassword,
            String loginUrl
        ) {
            this(userId, toEmail, fullName, createdAt, tempPassword, loginUrl, false);
        }
    }

    public record SendSecurityAlertEvent(
        UUID userId,
        String toEmail,
        String fullName,
        int failedCount,
        int windowMinutes,
        String unlockTime,
        String lastAttemptTime,
        String ipAddress,
        String location,
        String resetUrl,
        boolean isLocked
    ) {
    }

    public record SendAccountChangedEvent(
        UUID userId,
        String toEmail,
        String fullName,
        String changeTime,
        String fieldName,
        String oldValue,
        String newValue,
        boolean performedByAdmin,
        String device,
        String ipAddress,
        String accountUrl
    ) {
    }
}
