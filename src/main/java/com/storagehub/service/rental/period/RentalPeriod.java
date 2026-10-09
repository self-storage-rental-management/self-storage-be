package com.storagehub.service.rental.period;

import com.storagehub.domain.model.Rental;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal, record-proven period. Raw dates are never changed by normalization. */
public record RentalPeriod(UUID rentalId, LocalDate startDate, LocalDate storedEndDate,
    Convention convention, LocalDate lastPermittedDate, LocalDate endExclusive, String reference) {
    public enum Convention { INCLUSIVE, EXCLUSIVE }

    public static Optional<RentalPeriod> verified(UUID id, LocalDate start, LocalDate storedEnd,
        Convention convention, String reference) {
        if (id == null || start == null || storedEnd == null || convention == null
            || reference == null || reference.isBlank()) return Optional.empty();
        try {
            LocalDate end = convention == Convention.EXCLUSIVE ? storedEnd : storedEnd.plusDays(1);
            if (!end.isAfter(start)) return Optional.empty();
            return Optional.of(new RentalPeriod(id, start, storedEnd, convention, end.minusDays(1), end, reference));
        } catch (DateTimeException e) { return Optional.empty(); }
    }

    public boolean matches(Rental rental) {
        return rental != null && Objects.equals(rentalId, rental.getId())
            && Objects.equals(startDate, rental.getStartDate())
            && Objects.equals(storedEndDate, rental.getContractEndDate())
            && verified(rentalId, startDate, storedEndDate, convention, reference).filter(this::equals).isPresent();
    }

    public LocalDate storeEnd(LocalDate canonicalEndExclusive) {
        return convention == Convention.EXCLUSIVE ? canonicalEndExclusive : canonicalEndExclusive.minusDays(1);
    }
}
