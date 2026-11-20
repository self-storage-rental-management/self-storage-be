package com.storagehub.service.support.ai;

import static com.storagehub.service.support.ai.SupportPublicKnowledge.containsPhrase;
import static com.storagehub.service.support.ai.SupportPublicKnowledge.normalize;

import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AvailabilityQueryService;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class SupportPublicBooking {
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final SupportPublicFacility publicFacility;
    private final FacilityRepository facilities;
    private final UnitTypeRepository unitTypes;
    private final AvailabilityQueryService availability;

    @Transactional(readOnly = true)
    public String answer(ActorPrincipal customer, SupportAiProvider.Action action, SupportAiProvider.Lookup lookup, String question) {
        String subject = mentioned(question, lookup.facility()).orElse(null);
        if (subject == null) return "Bạn muốn xem ở cơ sở nào? Hãy cho mình tên hoặc mã cơ sở.";
        var contacts = publicFacility.contactsFor(subject);
        if (contacts.isEmpty()) return "Mình chưa tìm thấy cơ sở đang hoạt động phù hợp. Bạn kiểm tra lại tên hoặc mã cơ sở nhé.";
        if (contacts.size() > 1) {
            return "Bạn muốn xem cơ sở nào? Mã các cơ sở phù hợp: "
                + contacts.stream().limit(4).map(FacilityRepository.PublicContact::getCode).collect(Collectors.joining(", ")) + ".";
        }
        var facility = facilities.findByCodeIgnoreCase(contacts.getFirst().getCode())
            .filter(value -> value.getStatus() == FacilityStatus.active);
        if (facility.isEmpty()) return "Cơ sở hiện không nhận đơn mới. Bạn có thể chọn một cơ sở đang hoạt động.";

        String code = mentioned(question, lookup.unitType()).orElse(null);
        if (code == null) return "Bạn muốn xem cỡ kho nào tại **" + facility.get().getName() + "**?";
        var type = unitTypes.findByFacility_IdAndCode(facility.get().getId(), code.toUpperCase(Locale.ROOT))
            .filter(unit -> unit.getStatus() == UnitTypeStatus.active);
        if (type.isEmpty()) return "Mình chưa tìm thấy cỡ kho đang công bố phù hợp tại cơ sở này.";

        String label = "**" + type.get().getName() + "** tại **" + facility.get().getName() + "**";
        if (action == SupportAiProvider.Action.PRICE) {
            var price = type.get().getMonthlyPrice();
            if (price == null || price.signum() <= 0) return "Loại kho này chưa có đơn giá công bố.";
            var money = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
            return label + " có đơn giá niêm yết **" + money.format(price) + " ₫/tháng**. "
                + "Bạn có thể lấy báo giá trong màn **Cỡ kho khả dụng** để xem giá theo gói và thời gian thuê.";
        }

        var from = confirmedDate(question, lookup.startDate());
        var to = confirmedDate(question, lookup.endDate());
        if (from.isEmpty() || to.isEmpty()) return "Bạn cho mình ngày bắt đầu và ngày kết thúc thuê (ngày/tháng/năm) để kiểm tra gian trống nhé.";
        LocalDate start = from.get(), end = to.get();
        if (start.isBefore(LocalDate.now(LOCAL_ZONE)) || !end.isAfter(start) || end.isAfter(start.plusYears(2))) {
            return "Bạn chọn lại khoảng thời gian thuê hợp lệ rồi gửi ngày bắt đầu và ngày kết thúc nhé.";
        }
        var result = availability.get(customer, facility.get().getId(), type.get().getId(), start, end);
        return label + (result.available() ? " hiện có **" + result.availableCount() + " gian trống**" : " hiện hết gian trống")
            + " trong kỳ " + start.format(DISPLAY_DATE) + " – " + end.format(DISPLAY_DATE) + ".";
    }

    static Optional<String> mentioned(String question, String value) {
        if (value == null || !containsPhrase(normalize(question), normalize(value))) return Optional.empty();
        return Optional.of(value);
    }

    private static Optional<LocalDate> confirmedDate(String question, String extracted) {
        if (extracted == null) return Optional.empty();
        try {
            LocalDate date = LocalDate.parse(extracted);
            String day = "0?" + date.getDayOfMonth(), month = "0?" + date.getMonthValue();
            var explicit = Pattern.compile("(?<![0-9])(?:" + Pattern.quote(date.toString())
                + "|" + day + "[/.-]" + month + "[/.-]" + date.getYear() + ")(?![0-9])");
            return explicit.matcher(question).find() ? Optional.of(date) : Optional.empty();
        } catch (DateTimeParseException invalid) {
            return Optional.empty();
        }
    }
}
