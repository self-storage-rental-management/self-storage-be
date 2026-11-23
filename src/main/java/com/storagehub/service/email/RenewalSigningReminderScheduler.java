package com.storagehub.service.email;

import com.storagehub.domain.model.RenewalStatus;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RenewalSigningReminderScheduler {
    private final EntityManager em;
    private final CustomerLifecycleEmailService emails;

    @Scheduled(fixedDelayString = "${app.renewal.signing-reminder-delay-ms:60000}")
    @Transactional
    public void remind() {
        Instant now = Instant.now();
        Instant twoHours = now.plus(Duration.ofHours(2));
        var appointments = em.createQuery("""
            select s from RenewalOperationState s
            where s.phase=:phase and s.renewal.status=:status and s.appointmentRef is not null
              and (s.depositPaidAt is not null or s.fullRentPaidAt is not null)
              and s.arrivalRef is null and s.appointmentStart > :now and s.appointmentStart <= :tomorrow
            """, RenewalOperationState.class)
            .setParameter("phase", RenewalOperationState.Phase.SIGNING)
            .setParameter("status", RenewalStatus.appointment_scheduled)
            .setParameter("now", now).setParameter("tomorrow", now.plus(Duration.ofHours(24))).getResultList();
        for (var appointment : appointments) {
            boolean finalReminder = !appointment.getAppointmentStart().isAfter(twoHours);
            emails.signingReminder(appointment, finalReminder);
        }
    }
}
