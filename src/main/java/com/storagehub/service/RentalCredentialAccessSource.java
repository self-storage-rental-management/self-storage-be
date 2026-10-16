package com.storagehub.service;

import com.storagehub.domain.model.AccessCredentialStatus;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.repo.AccessCredentialRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Projects persisted credential state without exposing credential references or secrets. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalCredentialAccessSource implements RentalReadSources.AccessSource {
    private final AccessCredentialRepository credentials;
    private final ObjectProvider<Clock> clocks;

    @Override
    public Optional<RentalReadSources.Access> read(Rental rental) {
        RentalReadSources.AccessStatus status;
        if (!credentials.findByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.ACTIVE).isEmpty()) {
            status = RentalReadSources.AccessStatus.ACTIVE;
        } else if (!credentials.findByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.REVOKED).isEmpty()) {
            status = RentalReadSources.AccessStatus.REVOKED;
        } else {
            status = RentalReadSources.AccessStatus.INACTIVE;
        }
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        return Optional.of(new RentalReadSources.Access(rental.getId(), clock.instant(), status));
    }
}
