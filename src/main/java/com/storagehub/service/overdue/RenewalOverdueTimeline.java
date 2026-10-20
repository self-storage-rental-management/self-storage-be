package com.storagehub.service.overdue;

import com.storagehub.service.calendar.ServiceCalendarPolicy;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** Team policy: seven overdue calendar days, then Recovery eligibility on day eight. */
public final class RenewalOverdueTimeline {
    private RenewalOverdueTimeline() {}
    /** Single source for the fixed team policy exposed to BO and used by runtime workers. */
    public static com.storagehub.api.renewal.PublishedRenewalPolicy.Term policy() {
        return new com.storagehub.api.renewal.PublishedRenewalPolicy.Term("CALENDAR_DAYS","Asia/Ho_Chi_Minh",
            3,6,7,8,ServiceCalendarPolicy.CLOSE,ServiceCalendarPolicy.OPEN);
    }
    public static OverdueSources.Term term(String ref,String version,LocalDate lastDay,ZoneId zone) {
        var p=policy();var policyZone=ZoneId.of(p.timezone());
        return new OverdueSources.Term(ref,version,lastDay,p.warningThroughDay(),p.seriousThroughDay(),p.urgentThroughDay(),p.recoveryFromDay(),
            lastDay.plusDays(p.urgentThroughDay()).atTime(p.recoveryCutoffTime()).atZone(policyZone).toInstant(),
            lastDay.plusDays(p.recoveryFromDay()).atTime(p.recoveryStartTime()).atZone(policyZone).toInstant());
    }
    public static long overdueDays(LocalDate lastDay,Instant now,ZoneId zone) {
        return Math.max(0,ChronoUnit.DAYS.between(lastDay,now.atZone(zone).toLocalDate()));
    }
    public static String severity(long days,Instant now,Instant cutoff) {
        if(!now.isBefore(cutoff))return "RENEWAL_CLOSED";
        return days==0?"NONE":days<=3?"WARNING":days<=6?"SERIOUS":"URGENT";
    }
}
