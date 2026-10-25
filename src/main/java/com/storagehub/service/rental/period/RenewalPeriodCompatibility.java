package com.storagehub.service.rental.period;

import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import java.time.DateTimeException;
import java.util.Objects;

public final class RenewalPeriodCompatibility {
    private RenewalPeriodCompatibility() {}
    public static void require(RentalPeriod period, RenewalQuoteResponse.Terms terms) {
        try {
            boolean legacy=terms.endDateConvention()==null && terms.rentalStartDate()==null;
            if (legacy && period.convention()!=RentalPeriod.Convention.INCLUSIVE
                || !legacy && (!Objects.equals(terms.endDateConvention(),period.convention().name())
                    || !Objects.equals(terms.rentalStartDate(),period.startDate()))
                || !Objects.equals(terms.oldEndDate(),period.storedEndDate())
                || !Objects.equals(terms.extensionStartDate(),period.endExclusive())
                || terms.rentalMonths()<1 || terms.extensionEndExclusive()==null || terms.newEndDate()==null
                || !period.endExclusive().plusMonths(terms.rentalMonths()).equals(terms.extensionEndExclusive())
                || !terms.newEndDate().plusDays(1).equals(terms.extensionEndExclusive()))
                throw ApiExceptions.conflict("Accepted Rental date convention/period changed; requote required");
        } catch (DateTimeException e) {throw ApiExceptions.conflict("Accepted Rental dates are outside supported bounds");}
    }
}
