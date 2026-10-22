package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Pattern;

final class FacilityAppointmentHours {
    private FacilityAppointmentHours() {}
    static void validate(Instant appointment, String hours) {
        if (appointment == null || hours == null || hours.isBlank() || hours.matches("(?i).*24\\s*/\\s*7.*")) return;
        var matcher = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)").matcher(hours);
        if (!matcher.find()) return;
        int start = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
        if (!matcher.find()) return;
        int end = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
        var local = appointment.atZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        int minute = local.getHour() * 60 + local.getMinute();
        boolean allowed = start <= end ? minute >= start && minute < end : minute >= start || minute < end;
        if (!allowed) throw ApiExceptions.validation("Giờ nhận kho nằm ngoài giờ hoạt động của cơ sở (" + hours + "). Vui lòng chọn giờ khác.", null);
    }
}
