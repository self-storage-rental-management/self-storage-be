package com.storagehub.service;

import com.storagehub.domain.model.AccessCredentialStatus;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.repo.AccessCredentialRepository;
import com.storagehub.domain.repo.CheckInRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Projects persisted access state and the physical lock reference, never PIN or password secrets. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalCredentialAccessSource implements RentalReadSources.AccessSource {
    private final AccessCredentialRepository credentials;
    private final CheckInRepository checkIns;
    private final ObjectProvider<Clock> clocks;

    @Override
    public Optional<RentalReadSources.Access> read(Rental rental) {
        var activeCredentials = credentials.findByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.ACTIVE);
        RentalReadSources.AccessStatus status;
        if (!activeCredentials.isEmpty()) {
            status = RentalReadSources.AccessStatus.ACTIVE;
        } else if (!credentials.findByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.REVOKED).isEmpty()) {
            status = RentalReadSources.AccessStatus.REVOKED;
        } else {
            status = RentalReadSources.AccessStatus.INACTIVE;
        }
        String lockReference = activeCredentials.stream()
            .filter(credential -> credential.getType() == com.storagehub.domain.model.AccessCredentialType.LOCK)
            .map(com.storagehub.domain.model.AccessCredential::getReference)
            .filter(reference -> reference != null && !reference.isBlank())
            .findFirst()
            .orElse(null);
        // The lock is recorded at physical handover before the Rental is created.
        // Keep the Tram handover value visible when an older/migrated credential
        // was not linked to the Rental by attachToRental().
        boolean hasActiveLock = activeCredentials.stream()
            .anyMatch(credential -> credential.getType() == com.storagehub.domain.model.AccessCredentialType.LOCK);
        if (lockReference == null && hasActiveLock && rental.getReservation() != null) {
            lockReference = checkIns.findByReservation_Id(rental.getReservation().getId())
                .map(com.storagehub.domain.model.CheckIn::getLockReference)
                .filter(reference -> reference != null && !reference.isBlank())
                .orElse(null);
        }
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return Optional.of(new RentalReadSources.Access(rental.getId(), clock.instant(), status, lockReference));
    }
}
