package com.storagehub.api.renewal;

import com.storagehub.domain.model.FacilityStatus;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Published hours only; this response does not claim that an appointment can be reserved. */
public record ServiceCalendarPolicyResponse(
    UUID facilityId,
    FacilityStatus facilityStatus,
    String timezone,
    List<DayOfWeek> workingDays,
    LocalTime workingFrom,
    LocalTime workingTo,
    LocalTime firstAppointmentStart,
    LocalTime lastAppointmentStart,
    int slotStepMinutes,
    int signingDurationMinutes,
    int maximumConcurrentPerStaff,
    String availabilityStatus
) {}
