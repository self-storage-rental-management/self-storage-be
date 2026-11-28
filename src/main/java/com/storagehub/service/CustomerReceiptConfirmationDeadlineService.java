package com.storagehub.service;

import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.repo.CheckInRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Migrates legacy pending handovers; never releases units or asks customers to confirm. */
@Service
@RequiredArgsConstructor
public class CustomerReceiptConfirmationDeadlineService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CustomerReceiptConfirmationDeadlineService.class);
    private final CheckInRepository checkInRepository;
    private final NotificationService notificationService;
    @Autowired private CustomerReservationService customerReservations;
    public int notifyDueConfirmations() {
        int converted = 0;
        for (var id : checkInRepository.findPendingReceiptIds(CheckInStatus.handover_pending_receipt)) {
            try {
                var checkIn = checkInRepository.findById(id).orElseThrow();
                customerReservations.startPreparationAfterHandover(checkIn.getReservation().getId(), checkIn.getCheckedInAt(), checkIn.getPerformedBy());
                converted++;
            } catch (RuntimeException e) {
                log.warn("Legacy handover {} needs reconciliation before preparation can start", id, e);
            }
        }
        return converted;
    }
}
