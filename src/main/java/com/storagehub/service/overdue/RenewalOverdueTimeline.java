package com.storagehub.service.overdue;

import com.storagehub.service.calendar.ServiceCalendarPolicy;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** Team policy: seven overdue calendar days, then Recovery eligibility on day eight. */
public final class RenewalOverdueTimeline {
    private RenewalOverdueTimeline() {}
    public static OverdueSources.Term term(String ref,String version,LocalDate lastDay,ZoneId zone) {
        return new OverdueSources.Term(ref,version,lastDay,3,6,7,8,
            lastDay.plusDays(7).atTime(ServiceCalendarPolicy.CLOSE).atZone(zone).toInstant(),
            lastDay.plusDays(8).atTime(ServiceCalendarPolicy.OPEN).atZone(zone).toInstant());
    }
    public static long overdueDays(LocalDate lastDay,Instant now,ZoneId zone) {
        return Math.max(0,ChronoUnit.DAYS.between(lastDay,now.atZone(zone).toLocalDate()));
    }
    public static String severity(long days,Instant now,Instant cutoff) {
        if(!now.isBefore(cutoff))return "RENEWAL_CLOSED";
        return days==0?"NONE":days<=3?"WARNING":days<=6?"SERIOUS":"URGENT";
    }
}
