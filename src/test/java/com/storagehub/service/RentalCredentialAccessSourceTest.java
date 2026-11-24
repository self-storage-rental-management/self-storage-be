package com.storagehub.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.AccessCredentialRepository;
import com.storagehub.domain.repo.CheckInRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RentalCredentialAccessSourceTest {
    @Test
    @SuppressWarnings("unchecked")
    void projectsIssuedRevokedAndMissingCredentialsWithoutInferringFromRentalStatus() {
        var repository = mock(AccessCredentialRepository.class);
        var checkIns = mock(CheckInRepository.class);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        var clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
        when(clocks.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(clock);
        var source = new RentalCredentialAccessSource(repository, checkIns, clocks);
        var rental = mock(Rental.class);
        when(rental.getId()).thenReturn(UUID.randomUUID());

        assertEquals(RentalReadSources.AccessStatus.INACTIVE, source.read(rental).orElseThrow().status());
        when(repository.findByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.REVOKED))
            .thenReturn(List.of(new AccessCredential()));
        assertEquals(RentalReadSources.AccessStatus.REVOKED, source.read(rental).orElseThrow().status());
        when(repository.findByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.ACTIVE))
            .thenReturn(List.of(new AccessCredential()));
        var result = source.read(rental).orElseThrow();
        assertEquals(RentalReadSources.AccessStatus.ACTIVE, result.status());
        assertEquals(rental.getId(), result.rentalId());
        assertEquals(clock.instant(), result.checkedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToTheHandedOverCheckInLockReferenceWhenCredentialWasNotLinkedToRental() {
        var repository = mock(AccessCredentialRepository.class);
        var checkIns = mock(CheckInRepository.class);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        var clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
        when(clocks.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(clock);
        var source = new RentalCredentialAccessSource(repository, checkIns, clocks);

        var rental = mock(Rental.class);
        var reservation = mock(Reservation.class);
        var reservationId = UUID.randomUUID();
        when(rental.getId()).thenReturn(UUID.randomUUID());
        when(rental.getReservation()).thenReturn(reservation);
        when(reservation.getId()).thenReturn(reservationId);
        var activeCredential = new AccessCredential();
        activeCredential.setType(AccessCredentialType.LOCK);
        activeCredential.setReference(null);
        when(repository.findByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.ACTIVE))
            .thenReturn(List.of(activeCredential));
        var checkIn = mock(CheckIn.class);
        when(checkIn.getLockReference()).thenReturn("LOC-TRAM-001");
        when(checkIns.findByReservation_Id(reservationId)).thenReturn(Optional.of(checkIn));

        var result = source.read(rental).orElseThrow();
        assertEquals(RentalReadSources.AccessStatus.ACTIVE, result.status());
        assertEquals("LOC-TRAM-001", result.reference());
    }
}
