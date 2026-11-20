package com.storagehub.service.support.ai;

import static com.storagehub.service.support.ai.SupportPublicKnowledge.normalize;

import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.FacilityRepository.PublicContact;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class SupportPublicFacility {
    private static final Pattern HOURS = words("(?:may gio|bao nhieu gio|luc nao) (?:mo|dong) cua|"
        + "(khung )?gio mo va dong cua|khung gio hoat dong|gio (mo|dong) cua|"
        + "gio (hoat dong|lam viec|ra vao|truy cap)|tiep khach|mo cua|dong cua");
    private static final Pattern PHONE = words("so dien thoai lien he|so dien thoai|so hotline|hotline|so lien lac|so lien he|dien thoai lien he|so dt|lien lac qua so nao");
    private static final Pattern FACILITY = words("co so|chi nhanh|kho");
    private static final Pattern FILLER = words("cho (minh|toi|em) hoi|co the cho (minh|toi|em)|cho (minh|toi|em)( xin| biet)?|"
        + "tu may gio den may gio|tu may gio|den may gio|la bao nhieu|bao nhieu gio|bao nhieu|may gio|luc nao|so nao|so may|la gi|the nao|"
        + "khu vuc|o dau|xin hoi|o|tai|cua|va|voi|thi|la|ay|nhe|xin|duoc khong|khong|storagehub");
    private static final Pattern PRIVATE_OR_OTHER = words("cua (toi|minh|em|anh|chi|ban|khach|nguoi)|nhan vien|quan ly|tai khoan|otp|pin|"
        + "mat khau|gia han|tra kho|thanh toan|hop dong|tien coc|hom nay|ngay mai|ngay le|cuoi tuan|ba bay|chu nhat|"
        + "cua cuon|bi ket|khong mo cua|khong phan hoi|bi loi|bo qua|lenh|api|http|https|noi bo");
    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9]+(?: [a-z0-9]+){0,5}");
    private static final Pattern NON_SUBJECT = words("cam on|xin chao|khong biet|khong can|la gi|nhu the nao|"
        + "gia kho|gia thue|bao gia|bao nhieu|con gian|con kho|co the|co duoc|tu van");
    private static final String ASK_FACILITY = "Bạn muốn tra cứu cơ sở nào? Hãy gửi tên hoặc mã cơ sở.";
    private final FacilityRepository facilities;

    record Query(String subject, boolean hours, boolean phone, boolean explicitFacility) {}

    static Optional<Query> intent(String message) {
        String question = stripFollowUp(normalize(message));
        boolean hours = HOURS.matcher(question).find();
        boolean phone = PHONE.matcher(question).find();
        boolean facility = FACILITY.matcher(question).find();
        if ((!hours && !phone) || PRIVATE_OR_OTHER.matcher(question).find()) return Optional.empty();

        String subject = HOURS.matcher(question).replaceAll(" ");
        subject = PHONE.matcher(subject).replaceAll(" ");
        subject = FILLER.matcher(subject).replaceAll(" ");
        subject = FACILITY.matcher(subject).replaceAll(" ").replaceAll("\\s+", " ").trim().replaceFirst("^ma ", "");
        if (facility && (subject.equals("do") || subject.equals("nay") || subject.equals("tren"))) {
            subject = "";
        }
        if (subject.split(" ").length > 8) return Optional.empty();
        return Optional.of(new Query(subject, hours, phone, facility));
    }

    static Optional<Query> conversationQuery(String message, List<String> previous, SupportPublicKnowledge knowledge) {
        var current = intent(message);
        if (previous.isEmpty() || current.filter(query -> !query.subject().isEmpty()).isPresent()) return current;

        Optional<Query> recent = intent(previous.getFirst());
        if (recent.isEmpty() && previous.size() > 1) {
            recent = intent(previous.get(1)).flatMap(query -> withSubject(query, previous.getFirst(), knowledge));
        }
        if (recent.isEmpty()) return current;
        Query context = recent.get();
        if (current.isEmpty()) return withSubject(context, message, knowledge);
        if (context.subject().isEmpty()) return current;
        return current.map(query -> new Query(context.subject(), query.hours(), query.phone(), true));
    }

    private static Optional<Query> withSubject(Query context, String message, SupportPublicKnowledge knowledge) {
        String text = stripFollowUp(normalize(message));
        if (PRIVATE_OR_OTHER.matcher(text).find() || NON_SUBJECT.matcher(text).find()
            || !knowledge.candidates(text).isEmpty()) return Optional.empty();
        String subject = text.replaceFirst("^(?:(?:o|tai) )?(?:ma )?(?:(?:co so|chi nhanh|kho) )?", "");
        if (!SUBJECT.matcher(subject).matches()) return Optional.empty();
        return Optional.of(new Query(subject, context.hours(), context.phone(), false));
    }

    private static String stripFollowUp(String text) {
        return text.replaceFirst("^(?:(?:the|vay) )?con ", "").replaceFirst(" (?:thi )?sao$", "");
    }

    String answer(Query query) {
        if (query.subject().isEmpty()) return ASK_FACILITY;
        var matches = contactsFor(query.subject());
        if (matches.isEmpty()) {
            if (!query.explicitFacility()) return SupportPublicKnowledge.UNKNOWN;
            return "Mình chưa tìm thấy cơ sở đang hoạt động phù hợp. Bạn hãy kiểm tra lại tên hoặc mã cơ sở, hoặc chọn **Nhân viên** để được hỗ trợ.";
        }
        if (matches.size() > 1) {
            String choices = matches.stream().limit(4).map(SupportPublicFacility::label).collect(Collectors.joining(", "));
            return "Có " + matches.size() + " cơ sở phù hợp. Hãy gửi mã của cơ sở bạn muốn tra. "
                + (matches.size() > 4 ? "Một số cơ sở: " : "") + choices + ".";
        }
        PublicContact contact = matches.getFirst();
        var details = new ArrayList<String>();
        if (query.hours()) details.add(detail("Giờ mở cửa đăng ký", contact.getAccessHours()));
        if (query.phone()) details.add(detail("Số điện thoại liên hệ", contact.getPhone()));
        String answer = "**" + label(contact) + "**\n\n" + String.join("\n\n", details);
        if ((query.hours() && missing(contact.getAccessHours())) || (query.phone() && missing(contact.getPhone()))) {
            answer += "\n\nBạn có thể chọn **Nhân viên** để hỏi thêm.";
        }
        return answer;
    }

    List<PublicContact> contactsFor(String name) {
        String subject = FACILITY.matcher(normalize(name)).replaceAll(" ").replaceAll("\\s+", " ").trim();
        List<PublicContact> contacts = facilities.findPublicContacts(FacilityStatus.active);
        var matches = contacts.stream().filter(contact -> matchesName(subject, contact)).toList();
        return matches.isEmpty()
            ? contacts.stream().filter(contact -> matchesCity(subject, contact.getCity())).toList()
            : matches;
    }

    private static boolean matchesName(String subject, PublicContact contact) {
        String code = normalize(contact.getCode());
        String fullName = normalize(contact.getName());
        String shortName = fullName.replaceFirst("^.*\\b(?:co so|chi nhanh) ", "");
        String name = FACILITY.matcher(fullName).replaceAll(" ");
        name = words("storagehub").matcher(name).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String city = contact.getCity() == null ? "" : normalize(contact.getCity());
        return subject.equals(code) || subject.equals(code.replace(" ", "")) || subject.equals(code + " a")
            || subject.equals(shortName) || subject.equals(shortName + " a")
            || (!name.isEmpty() && (subject.equals(name) || subject.equals(name + " a") || subject.equals(name + " " + city)));
    }

    private static boolean matchesCity(String subject, String city) {
        if (city == null) return false;
        String name = stripCityPrefix(normalize(city));
        return stripCityPrefix(subject).equals(name) || stripCityPrefix(subject).equals(name + " a");
    }

    private static String stripCityPrefix(String city) {
        return city.replaceFirst("^(tp|thanh pho|tinh) ", "");
    }

    private static String label(PublicContact contact) {
        return contact.getName() + " (" + contact.getCode() + ")";
    }

    private static String detail(String label, String value) {
        return label + ": " + (missing(value) ? "Chưa công bố." : value.trim());
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static Pattern words(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}
