package com.storagehub.service.rental.period;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.RentalReadSources;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class RentalPeriodResolver {
    private final ObjectProvider<RentalReadSources.DateSource> sources;
    public Optional<RentalPeriod> read(Rental rental) {
        var source = sources.getIfAvailable();
        return source == null ? Optional.empty() : source.read(rental).flatMap(d -> normalize(rental, d));
    }
    public RentalPeriod require(Rental rental) {
        return read(rental).orElseThrow(() -> ApiExceptions.conflict("DEFERRED_SOURCE: Rental date provenance is unknown"));
    }
    public static Optional<RentalPeriod> normalize(Rental rental, RentalReadSources.Dates evidence) {
        if (evidence == null) return Optional.empty();
        return RentalPeriod.verified(evidence.rentalId(), evidence.startDate(), evidence.storedEndDate(),
            evidence.convention(), evidence.reference()).filter(p -> p.matches(rental)
                && p.lastPermittedDate().equals(evidence.inclusiveEndDate()));
    }
}
