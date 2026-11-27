package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.ReturnFeeScheduleVersion;
import com.storagehub.domain.repo.ReturnFeeScheduleVersionRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReturnFeeScheduleService {
    private final ReturnFeeScheduleVersionRepository versions;
    private final ObjectMapper mapper;
    public record Detail(String label, String trigger, String applies) {}
    public record Schedule(long revision, Map<String, BigDecimal> prices, Map<String, Detail> details, String publishedAt) {
        public Schedule {
            if (prices != null) prices = Collections.unmodifiableMap(new LinkedHashMap<>(prices));
            if (details != null) details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
        public Schedule(long revision, Map<String, BigDecimal> prices) { this(revision, prices, null, null); }
    }

    public Schedule defaults() {
        var prices = new LinkedHashMap<String, BigDecimal>();
        var details = new LinkedHashMap<String, Detail>();
        ReturnDamagePricing.catalog().forEach((code, item) -> {
            prices.put(code, item.unitPrice());
            details.put(code, new Detail(item.label(), "Ghi nhận khi nghiệm thu: " + item.label(), "Theo số lượng " + item.unit() + " hư hỏng thực tế"));
        });
        prices.put("clean", BigDecimal.ZERO);
        prices.put("minor", BigDecimal.valueOf(100000));
        prices.put("major", BigDecimal.valueOf(200000));
        details.put("clean", new Detail("Kho sạch", "Không cần vệ sinh bổ sung", "Kho trả đạt yêu cầu vệ sinh"));
        details.put("minor", new Detail("Vệ sinh nhẹ", "Kho cần vệ sinh nhẹ", "Kho cần dọn vệ sinh thông thường"));
        details.put("major", new Detail("Tổng vệ sinh", "Kho cần tổng vệ sinh hoặc còn hàng", "Kho cần vệ sinh nặng, xử lý hàng bỏ lại"));
        return new Schedule(0, prices, details, null);
    }

    @Transactional(readOnly = true)
    public Schedule get() {
        return decode(versions.findFirstByOrderByRevisionDesc().orElseThrow(this::missingBaseline));
    }

    /** Strict boundary: only reservations created AFTER publication use it. */
    @Transactional(readOnly = true)
    public Schedule applicableAt(Instant reservationCreatedAt) {
        if (reservationCreatedAt == null) return baseline();
        return versions.findFirstByPublishedAtLessThanOrderByPublishedAtDescRevisionDesc(reservationCreatedAt)
            .map(this::decode).orElseGet(this::baseline);
    }

    @Transactional(readOnly = true)
    public Schedule applicableTo(Rental rental) {
        // Unknown legacy reservation dates must never receive newly published prices.
        return applicableAt(rental == null || rental.getReservation() == null ? null : rental.getReservation().getCreatedAt());
    }

    private Schedule baseline() {
        return decode(versions.findById(0L).orElseThrow(this::missingBaseline));
    }

    private RuntimeException missingBaseline() {
        return ApiExceptions.conflict("Chưa khởi tạo lịch sử biểu phí trả kho.");
    }

    private Schedule decode(ReturnFeeScheduleVersion version) {
        try {
            var value = version.getPayload() == null ? defaults() : mapper.readValue(version.getPayload(), Schedule.class);
            var details = value.details() == null && version.getRevision() == 0 ? defaults().details() : value.details();
            if (value.prices() == null || details == null) throw new IllegalStateException("Incomplete schedule");
            return new Schedule(version.getRevision(), value.prices(), details,
                version.getPublishedAt() == null ? null : version.getPublishedAt().toString());
        }
        catch (Exception e) { throw ApiExceptions.conflict("Không thể đọc biểu phí trả kho."); }
    }

    @Transactional
    public Schedule save(Schedule input, UUID publisherId) {
        // The migration seeds this row, serializing even first publications across instances.
        versions.lockBaseline().orElseThrow(this::missingBaseline);
        var current = get();
        if (input == null || input.revision() != current.revision()) throw ApiExceptions.conflict("Biểu phí đã thay đổi, vui lòng tải lại.");
        if (input.prices() == null || !input.prices().keySet().equals(defaults().prices().keySet())
            || input.details() == null || !input.details().keySet().equals(defaults().prices().keySet()))
            throw ApiExceptions.validation("Danh sách hạng mục phí không hợp lệ.", null);
        for (var amount : input.prices().values()) {
            if (amount == null || amount.signum() < 0 || amount.compareTo(BigDecimal.valueOf(999999999)) > 0 || amount.stripTrailingZeros().scale() > 0)
                throw ApiExceptions.validation("Mức phí phải là số nguyên không âm.", null);
        }
        if (input.prices().get("clean").signum() != 0 || input.prices().get("minor").signum() <= 0 || input.prices().get("minor").compareTo(input.prices().get("major")) >= 0)
            throw ApiExceptions.validation("Kho sạch có phí 0 đồng; phí vệ sinh nhẹ phải lớn hơn 0 và nhỏ hơn phí vệ sinh nặng.", null);
        for (var detail : input.details().values()) {
            if (detail == null || !validText(detail.label(), 160) || !validText(detail.trigger(), 1000) || !validText(detail.applies(), 1000))
                throw ApiExceptions.validation("Tên (160 ký tự), điều kiện và đối tượng áp dụng (1000 ký tự) không được để trống.", null);
        }
        var publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (current.publishedAt() != null && !publishedAt.isAfter(Instant.parse(current.publishedAt())))
            publishedAt = Instant.parse(current.publishedAt()).plus(1, ChronoUnit.MICROS);
        var next = new Schedule(current.revision() + 1, input.prices(), input.details(), publishedAt.toString());
        try {
            versions.saveAndFlush(new ReturnFeeScheduleVersion(next.revision(), publishedAt,
                mapper.writeValueAsString(next), publisherId == null ? null : publisherId.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        return next;
    }

    private boolean validText(String text, int max) {
        return text != null && !text.isBlank() && text.length() <= max;
    }
}
