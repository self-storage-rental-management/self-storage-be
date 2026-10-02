package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.PaymentComplaint;
import com.storagehub.domain.model.PaymentComplaintStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.repo.PaymentComplaintRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentComplaintDeadlineServiceTests {
    @Mock PaymentComplaintRepository repository;
    @Mock AuditLogService auditLogService;

    @Test
    void marksPendingComplaintOverdueWithoutReleasingReservation() {
        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        Reservation reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setFacility(facility);
        PaymentComplaint complaint = new PaymentComplaint();
        ReflectionTestUtils.setField(complaint, "id", UUID.randomUUID());
        complaint.setReservation(reservation);
        complaint.setStatus(PaymentComplaintStatus.PENDING);
        when(repository.findTop100ByStatusAndReviewDueAtLessThanEqualOrderByReviewDueAtAsc(
            any(), any())).thenReturn(List.of(complaint));

        int count = new PaymentComplaintDeadlineService(repository, auditLogService)
            .markOverdueComplaints();

        assertThat(count).isEqualTo(1);
        assertThat(complaint.getStatus()).isEqualTo(PaymentComplaintStatus.REVIEW_OVERDUE);
    }
}
