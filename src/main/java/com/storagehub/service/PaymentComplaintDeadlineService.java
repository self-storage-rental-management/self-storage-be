package com.storagehub.service;

import com.storagehub.domain.model.PaymentComplaint;
import com.storagehub.domain.model.PaymentComplaintStatus;
import com.storagehub.domain.repo.PaymentComplaintRepository;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentComplaintDeadlineService {
    private final PaymentComplaintRepository complaintRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public int markOverdueComplaints() {
        var due = complaintRepository
            .findTop100ByStatusAndReviewDueAtLessThanEqualOrderByReviewDueAtAsc(
                PaymentComplaintStatus.PENDING, Instant.now()
            );
        for (PaymentComplaint complaint : due) {
            complaint.setStatus(PaymentComplaintStatus.REVIEW_OVERDUE);
            auditLogService.recordMutation(
                null, "PAYMENT_COMPLAINT_OVERDUE", "PaymentComplaint", complaint.getId(),
                complaint.getReservation().getFacility().getId(),
                Map.of("status", PaymentComplaintStatus.PENDING),
                Map.of("status", PaymentComplaintStatus.REVIEW_OVERDUE)
            );
        }
        if (!due.isEmpty()) complaintRepository.saveAll(due);
        return due.size();
    }
}
