package com.storagehub.service.email;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReservationEmailReminderScheduler {
    private final EntityManager em;
    private final CustomerLifecycleEmailService emails;

    @Scheduled(fixedDelayString = "${app.reservation.email-reminder-delay-ms:30000}")
    @Transactional
    public void remind() {
        Instant now = Instant.now();
        var due = em.createQuery("select r from Reservation r where r.status=:status and r.paymentExpiresAt>:now and r.paymentExpiresAt<=:soon", Reservation.class)
            .setParameter("status", ReservationStatus.AWAITING_PAYMENT).setParameter("now", now)
            .setParameter("soon", now.plusSeconds(300)).setMaxResults(100).getResultList();
        for (Reservation reservation : due) emails.expiring(reservation);
    }
}
