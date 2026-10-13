package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RenewalOverdueTimelineTests {
    private static final ZoneId VIETNAM=ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate LAST_DAY=LocalDate.of(2026,10,10);

    @Test void seventhDayClosesAtFiveAndRecoveryBecomesEligibleOnEighthMorning() {
        var term=RenewalOverdueTimeline.term("policy","1",LAST_DAY,VIETNAM);
        assertThat(term.recoveryCutoff()).isEqualTo(Instant.parse("2026-10-17T10:00:00Z"));
        assertThat(term.recoveryStart()).isEqualTo(Instant.parse("2026-10-18T01:00:00Z"));
        assertThat(term.recoveryFromDay()).isEqualTo(8);
        assertThat(RenewalOverdueTimeline.severity(7,term.recoveryCutoff().minusNanos(1),term.recoveryCutoff())).isEqualTo("URGENT");
        assertThat(RenewalOverdueTimeline.severity(7,term.recoveryCutoff(),term.recoveryCutoff())).isEqualTo("RENEWAL_CLOSED");
    }

    @Test void severityUsesLocalCalendarDayAndDoesNotMarkRecoveryAsStarted() {
        var cutoff=RenewalOverdueTimeline.term("policy","1",LAST_DAY,VIETNAM).recoveryCutoff();
        for(var row:new Object[][]{{"2026-10-10T16:59:59Z",0L,"NONE"},{"2026-10-10T17:00:00Z",1L,"WARNING"},
            {"2026-10-13T03:00:00Z",3L,"WARNING"},{"2026-10-14T03:00:00Z",4L,"SERIOUS"},
            {"2026-10-16T03:00:00Z",6L,"SERIOUS"},{"2026-10-17T03:00:00Z",7L,"URGENT"},
            {"2026-10-18T03:00:00Z",8L,"RENEWAL_CLOSED"}}) {
            var now=Instant.parse((String)row[0]);
            var days=RenewalOverdueTimeline.overdueDays(LAST_DAY,now,VIETNAM);
            assertThat(days).isEqualTo(row[1]);
            assertThat(RenewalOverdueTimeline.severity(days,now,cutoff)).isEqualTo(row[2]);
        }
    }
}
