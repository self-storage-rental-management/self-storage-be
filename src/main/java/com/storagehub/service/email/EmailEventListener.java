package com.storagehub.service.email;

import com.storagehub.service.email.event.EmailEvents.SendAccountChangedEvent;
import com.storagehub.service.email.event.EmailEvents.SendAccountCreatedEvent;
import com.storagehub.service.email.event.EmailEvents.SendNewLoginEvent;
import com.storagehub.service.email.event.EmailEvents.SendPasswordResetEvent;
import com.storagehub.service.email.event.EmailEvents.SendSecurityAlertEvent;
import com.storagehub.service.email.event.EmailEvents.SendVerifyEmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailEventListener {

    private final TransactionalEmailService transactionalEmailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVerifyEmail(SendVerifyEmailEvent event) {
        log.debug("Dispatching verify-email event for recipient={}", event.toEmail());
        transactionalEmailService.sendVerifyEmailAsync(
            event.userId(),
            event.toEmail(),
            event.fullName(),
            event.verifyUrl(),
            event.expiryMinutes()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordReset(SendPasswordResetEvent event) {
        log.debug("Dispatching reset-password event for recipient={}", event.toEmail());
        transactionalEmailService.sendPasswordResetAsync(
            event.userId(),
            event.toEmail(),
            event.fullName(),
            event.resetUrl(),
            event.expiryMinutes(),
            event.otp()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNewLogin(SendNewLoginEvent event) {
        log.debug("Dispatching new-login event for recipient={}", event.toEmail());
        transactionalEmailService.sendNewLoginNoticeAsync(
            event.userId(),
            event.toEmail(),
            event.fullName(),
            event.loginTime(),
            event.device(),
            event.browser(),
            event.location(),
            event.ipAddress(),
            event.securityUrl()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountCreated(SendAccountCreatedEvent event) {
        log.debug("Dispatching account-created event for recipient={}, isReset={}", event.toEmail(), event.isReset());
        transactionalEmailService.sendAccountCreatedAsync(
            event.userId(),
            event.toEmail(),
            event.fullName(),
            event.createdAt(),
            event.tempPassword(),
            event.loginUrl(),
            event.isReset()
        );
    }

    @org.springframework.context.event.EventListener
    public void onSecurityAlert(SendSecurityAlertEvent event) {
        log.debug("Dispatching security-alert event for recipient={}", event.toEmail());
        transactionalEmailService.sendSecurityAlertAsync(
            event.userId(),
            event.toEmail(),
            event.fullName(),
            event.failedCount(),
            event.windowMinutes(),
            event.unlockTime(),
            event.lastAttemptTime(),
            event.ipAddress(),
            event.location(),
            event.resetUrl(),
            event.isLocked()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountChanged(SendAccountChangedEvent event) {
        log.debug("Dispatching account-changed event for recipient={}", event.toEmail());
        transactionalEmailService.sendAccountChangedAsync(
            event.userId(),
            event.toEmail(),
            event.fullName(),
            event.changeTime(),
            event.fieldName(),
            event.oldValue(),
            event.newValue(),
            event.performedByAdmin(),
            event.device(),
            event.ipAddress(),
            event.accountUrl()
        );
    }
}
