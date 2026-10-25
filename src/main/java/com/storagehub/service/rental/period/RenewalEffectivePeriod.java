package com.storagehub.service.rental.period;

import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.time.BusinessTime;
import java.time.*;

/** Accepted dates are a proposal; the signing command fixes the effective interval once. */
public final class RenewalEffectivePeriod {
    private RenewalEffectivePeriod() {}
    public record Interval(LocalDate startDate,LocalDate endExclusive,LocalDate newEndDate) {}
    public static LocalDate start(RentalPeriod previous,Instant signingAt) {
        LocalDate day=signingAt.atZone(BusinessTime.ZONE).toLocalDate();
        return day.isAfter(previous.endExclusive())?day:previous.endExclusive();
    }
    public static Interval at(RentalPeriod previous,RenewalQuoteResponse.Terms terms,Instant signingAt) {
        RenewalPeriodCompatibility.require(previous,terms);
        LocalDate start=start(previous,signingAt);
        if(terms.monthly()&&!start.equals(terms.extensionStartDate()))
            throw ApiExceptions.conflict("Điều khoản trả từng tháng cũ cần được đối soát trước khi đổi kỳ gia hạn.");
        LocalDate end=start.plusMonths(terms.rentalMonths());
        return new Interval(start,end,end.minusDays(1));
    }
}
