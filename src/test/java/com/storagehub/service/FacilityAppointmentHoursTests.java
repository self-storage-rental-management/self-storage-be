package com.storagehub.service;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FacilityAppointmentHoursTests {
    @Test void rejectsOutsideHoursInVietnamTimezone() {
        assertThatThrownBy(() -> FacilityAppointmentHours.validate(Instant.parse("2026-10-16T01:30:00Z"), "09:00 - 16:00"))
            .hasMessageContaining("ngoài giờ hoạt động");
        assertThatCode(() -> FacilityAppointmentHours.validate(Instant.parse("2026-10-16T02:00:00Z"), "09:00 - 16:00")).doesNotThrowAnyException();
        assertThatThrownBy(() -> FacilityAppointmentHours.validate(Instant.parse("2026-10-16T09:00:00Z"), "09:00 - 16:00"))
            .hasMessageContaining("ngoài giờ hoạt động");
    }
}
