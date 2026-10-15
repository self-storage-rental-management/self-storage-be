package com.storagehub.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.AccessCredentialRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RentalCredentialAccessSourceTest {
    @Test
    @SuppressWarnings("unchecked")
    void projectsIssuedRevokedAndMissingCredentialsWithoutInferringFromRentalStatus() {
        var repository = mock(AccessCredentialRepository.class);
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        var clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
        when(clocks.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(clock);
        var source = new RentalCredentialAccessSource(repository, clocks);
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
}
