package com.storagehub.service.email;

import com.storagehub.service.email.event.EmailEvents.SendAccountChangedEvent;
import com.storagehub.service.email.event.EmailEvents.SendAccountCreatedEvent;
import com.storagehub.service.email.event.EmailEvents.SendNewLoginEvent;
import com.storagehub.service.email.event.EmailEvents.SendPasswordResetEvent;
import com.storagehub.service.email.event.EmailEvents.SendSecurityAlertEvent;
import com.storagehub.service.email.event.EmailEvents.SendVerifyEmailEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final ApplicationEventPublisher eventPublisher;

    public void sendVerifyEmail(UUID userId, String toEmail, String fullName, String verifyUrl, int expiryMinutes) {
        eventPublisher.publishEvent(new SendVerifyEmailEvent(userId, toEmail, fullName, verifyUrl, expiryMinutes));
    }

    public void sendVerifyEmail(String toEmail, String fullName, String verifyUrl, int expiryMinutes) {
        sendVerifyEmail(null, toEmail, fullName, verifyUrl, expiryMinutes);
    }

    public void sendPasswordReset(UUID userId, String toEmail, String fullName, String resetUrl, int expiryMinutes) {
        sendPasswordReset(userId, toEmail, fullName, resetUrl, expiryMinutes, null);
    }

    public void sendPasswordReset(
        UUID userId,
        String toEmail,
        String fullName,
        String resetUrl,
        int expiryMinutes,
        String otp
    ) {
        eventPublisher.publishEvent(new SendPasswordResetEvent(
            userId, toEmail, fullName, resetUrl, expiryMinutes, otp
        ));
    }

    public void sendPasswordReset(String toEmail, String fullName, String resetUrl, int expiryMinutes) {
        sendPasswordReset(null, toEmail, fullName, resetUrl, expiryMinutes);
    }

    public void sendNewLoginNotice(
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
        eventPublisher.publishEvent(new SendNewLoginEvent(
            userId, toEmail, fullName, loginTime, device, browser, location, ipAddress, securityUrl
        ));
    }

    public void sendNewLoginNotice(
        String toEmail,
        String fullName,
        String loginTime,
        String device,
        String browser,
        String location,
        String ipAddress,
        String securityUrl
    ) {
        sendNewLoginNotice(null, toEmail, fullName, loginTime, device, browser, location, ipAddress, securityUrl);
    }

    public void sendAccountCreated(
        UUID userId,
        String toEmail,
        String fullName,
        String createdAt,
        String tempPassword,
        String loginUrl
    ) {
        sendAccountCreated(userId, toEmail, fullName, createdAt, tempPassword, loginUrl, false);
    }

    public void sendAccountCreated(
        UUID userId,
        String toEmail,
        String fullName,
        String createdAt,
        String tempPassword,
        String loginUrl,
        boolean isReset
    ) {
        eventPublisher.publishEvent(new SendAccountCreatedEvent(
            userId, toEmail, fullName, createdAt, tempPassword, loginUrl, isReset
        ));
    }

    public void sendAccountPasswordReset(
        UUID userId,
        String toEmail,
        String fullName,
        String resetTime,
        String tempPassword,
        String loginUrl
    ) {
        sendAccountCreated(userId, toEmail, fullName, resetTime, tempPassword, loginUrl, true);
    }

    public void sendAccountCreated(
        String toEmail,
        String fullName,
        String createdAt,
        String tempPassword,
        String loginUrl
    ) {
        sendAccountCreated(null, toEmail, fullName, createdAt, tempPassword, loginUrl, false);
    }

    public void sendSecurityAlert(
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
        eventPublisher.publishEvent(new SendSecurityAlertEvent(
            userId, toEmail, fullName, failedCount, windowMinutes, unlockTime,
            lastAttemptTime, ipAddress, location, resetUrl, isLocked
        ));
    }

    public void sendSecurityAlert(
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
        sendSecurityAlert(
            null, toEmail, fullName, failedCount, windowMinutes, unlockTime,
            lastAttemptTime, ipAddress, location, resetUrl, isLocked
        );
    }

    public void sendAccountChanged(
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
        eventPublisher.publishEvent(new SendAccountChangedEvent(
            userId, toEmail, fullName, changeTime, fieldName, oldValue, newValue,
            performedByAdmin, device, ipAddress, accountUrl
        ));
    }

    public void sendAccountChanged(
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
        sendAccountChanged(
            null, toEmail, fullName, changeTime, fieldName, oldValue, newValue,
            performedByAdmin, device, ipAddress, accountUrl
        );
    }
}
