package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.Renewal;
import com.storagehub.domain.model.RenewalDisplaySequence;
import com.storagehub.domain.model.RenewalStatus;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.repo.RenewalRepository;
import com.storagehub.service.BusinessRecordCodes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RenewalDisplayIdentityTests {

    private final RenewalRepository renewals = mock(RenewalRepository.class);
    private final BusinessRecordCodes codes = mock(BusinessRecordCodes.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RenewalDisplayIdentity identities = new RenewalDisplayIdentity(renewals, codes, entityManager);

    @Test
    void completedRenewalUsesItsPersistedSequence() {
        UUID rentalId = UUID.randomUUID();
        Renewal renewal = renewal(rentalId, RenewalStatus.completed, 2);
        when(codes.find("TH:" + rentalId)).thenReturn("SH-184033");

        var identity = identities.resolve(renewal);

        assertThat(identity.rentalDisplayCode()).isEqualTo("SH-184033");
        assertThat(identity.renewalSequence()).isEqualTo(2);
        assertThat(identity.label()).isEqualTo("SH-184033 · Gia hạn lần 2");
        verify(renewals, never()).findMaxDisplaySequence(any());
    }

    @Test
    void rejectedCancelledAndExpiredRequestsDoNotConsumeAnOccurrence() {
        UUID rentalId = UUID.randomUUID();
        when(codes.find("TH:" + rentalId)).thenReturn("SH-184033");

        for (var status : new RenewalStatus[]{RenewalStatus.rejected, RenewalStatus.cancelled, RenewalStatus.payment_expired}) {
            var identity = identities.resolve(renewal(rentalId, status, null));
            assertThat(identity.renewalSequence()).as(status.name()).isNull();
            assertThat(identity.label()).isEqualTo("SH-184033 · Gia hạn");
        }
        verify(renewals, never()).findMaxDisplaySequence(any());
    }

    @Test
    void activeRequestShowsTheNextProspectiveCompletedOccurrence() {
        UUID rentalId = UUID.randomUUID();
        var counter = new RenewalDisplaySequence(rentalId, 1);
        when(renewals.findMaxDisplaySequence(rentalId)).thenReturn(1);
        when(entityManager.find(RenewalDisplaySequence.class, rentalId)).thenReturn(counter);
        when(codes.find("TH:" + rentalId)).thenReturn("SH-184033");

        var identity = identities.resolve(renewal(rentalId, RenewalStatus.appointment_scheduled, null));

        assertThat(identity.renewalSequence()).isEqualTo(2);
        assertThat(identity.label()).isEqualTo("SH-184033 · Gia hạn lần 2");
    }

    @Test
    void completionAssignmentIsPersistedAndIdempotent() {
        UUID rentalId = UUID.randomUUID();
        Rental rental = mock(Rental.class);
        Renewal renewal = mock(Renewal.class);
        var assigned = new AtomicReference<Integer>();
        var counter = new RenewalDisplaySequence(rentalId, 1);
        when(rental.getId()).thenReturn(rentalId);
        when(renewal.getId()).thenReturn(UUID.randomUUID());
        when(renewal.getRental()).thenReturn(rental);
        when(renewal.getStatus()).thenReturn(RenewalStatus.completed);
        when(renewal.getDisplaySequence()).thenAnswer(invocation -> assigned.get());
        doAnswer(invocation -> { assigned.set(invocation.getArgument(0)); return null; })
            .when(renewal).setDisplaySequence(anyInt());
        when(renewals.findMaxDisplaySequence(rentalId)).thenReturn(1);
        when(entityManager.find(RenewalDisplaySequence.class, rentalId, LockModeType.PESSIMISTIC_WRITE))
            .thenReturn(counter);

        assertThat(identities.assignCompletedSequence(renewal)).isEqualTo(2);
        assertThat(identities.assignCompletedSequence(renewal)).isEqualTo(2);

        assertThat(assigned.get()).isEqualTo(2);
        assertThat(counter.getLastSequence()).isEqualTo(2);
        verify(entityManager, times(1)).lock(rental, LockModeType.PESSIMISTIC_WRITE);
        verify(renewals, times(1)).findMaxDisplaySequence(rentalId);
    }

    @Test
    void missingRenewalLookupReturnsEmpty() {
        UUID renewalId = UUID.randomUUID();
        when(renewals.findById(renewalId)).thenReturn(Optional.empty());

        assertThat(identities.find(renewalId)).isEmpty();
        assertThat(identities.find(null)).isEmpty();
    }

    private static Renewal renewal(UUID rentalId, RenewalStatus status, Integer displaySequence) {
        Rental rental = mock(Rental.class);
        Renewal renewal = mock(Renewal.class);
        when(rental.getId()).thenReturn(rentalId);
        when(renewal.getId()).thenReturn(UUID.randomUUID());
        when(renewal.getRental()).thenReturn(rental);
        when(renewal.getStatus()).thenReturn(status);
        when(renewal.getDisplaySequence()).thenReturn(displaySequence);
        return renewal;
    }
}
