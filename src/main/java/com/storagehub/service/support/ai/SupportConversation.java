package com.storagehub.service.support.ai;

import static com.storagehub.service.support.ai.SupportPublicKnowledge.normalize;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SupportConversation {
    private static final Pattern CAPABILITIES = Pattern.compile(
        "(?:cho (?:minh|toi|em) hoi )?(?:(?:vay )?(?:ban|tro ly(?: storagehub)?|bot|ai) "
        + "(?:co the (?:lam (?:duoc )?gi|(?:ho tro|giup)(?: (?:minh|toi|em))? (?:gi|duoc gi))"
        + "|(?:ho tro|giup)(?: (?:minh|toi|em))? (?:duoc )?gi(?: duoc)?(?: cho (?:minh|toi|em))?|biet lam gi|lam duoc gi)"
        + "|(?:cac )?chuc nang cua (?:ban|tro ly(?: storagehub)?|bot|ai)(?: la gi)?)(?: a| vay| the)?");
    private static final Pattern CAPACITY_TOPIC = Pattern.compile(
        "\\b(?:co kho|kich thuoc kho|dien tich kho|chon kho|loai kho|kho (?:s|m|l|xl))\\b");
    private static final Pattern CAPACITY_FOLLOW_UP = Pattern.compile(
        "(?:(?:vay|the|con) )?(?:(?:kho|gian|co)(?: kho)? (?:s|m|l|xl)(?: thi)? )?"
        + "(?:co )?(?:vua|du|chua duoc)(?: do| hang| cac kien nay| khong| ko| k)*(?: a| vay)?");
    private static final Pattern NAMED_SIZE = Pattern.compile("\\b(?:kho|gian|co) (?:kho )?(?:s|m|l|xl)\\b");
    private static final Pattern PACKAGE_COUNT = Pattern.compile("\\b([1-9][0-9]{0,2}) (?:thung|kien|hop|bao)\\b");
    private static final String INTRO = """
        Mình có thể hướng dẫn bạn **chọn cỡ kho**, **thanh toán**, **gia hạn** hoặc **trả kho**. Mình cũng có thể tra **giờ mở cửa và số điện thoại** của các cơ sở đang hoạt động.

        Bạn có thể hỏi “Chọn cỡ kho thế nào?” hoặc “Cơ sở Quận 1 mở cửa mấy giờ?”. Cần hỗ trợ về đơn hoặc hồ sơ cụ thể, bạn có thể chọn **Nhân viên**.""";
    private static final String ASK_SIZE = "Bạn có bao nhiêu kiện? Hãy cho mình biết kích thước (dài × rộng × cao) "
        + "và cân nặng mỗi loại kiện. Bạn có thể nhập số liệu vào **Tìm gian kho trống** để hệ thống kiểm tra sức chứa.";

    private SupportConversation() {}

    record Context(List<String> topics, Integer packageCount) {}

    static List<String> previousQuestions(List<SupportAiProvider.Message> messages) {
        return messages.reversed().stream().skip(1).filter(message -> message.role().equals("user"))
            .limit(2).map(SupportAiProvider.Message::content).toList();
    }

    static Optional<String> answer(String message, List<String> previous) {
        String question = normalize(message);
        if (CAPABILITIES.matcher(question).matches()) return Optional.of(introduction());
        if (CAPACITY_FOLLOW_UP.matcher(question).matches()
            && (NAMED_SIZE.matcher(question).find() || (!previous.isEmpty()
                && CAPACITY_TOPIC.matcher(normalize(previous.getFirst())).find()))) {
            if (!previous.isEmpty() && packageCountOf(previous.getFirst()).isPresent()) {
                return Optional.of("Bạn cho mình biết kích thước (dài × rộng × cao) và cân nặng mỗi loại kiện nhé. "
                    + "Bạn có thể nhập số liệu vào **Tìm gian kho trống** để hệ thống kiểm tra sức chứa.");
            }
            return Optional.of(ASK_SIZE);
        }
        return Optional.empty();
    }

    static String introduction() {
        return INTRO.strip();
    }

    static Context publicContext(List<SupportAiProvider.Message> messages, SupportPublicKnowledge knowledge) {
        var previous = previousQuestions(messages);
        if (previous.isEmpty()) return new Context(List.of(), null);
        String latest = previous.getFirst();
        var topics = knowledge.candidates(latest).stream().map(SupportPublicKnowledge.Entry::id).limit(3).toList();
        var count = packageCountOf(latest);
        if (topics.isEmpty() && count.isPresent()) topics = List.of("faq-unit-capacity");
        return new Context(topics, count.orElse(null));
    }

    private static Optional<Integer> packageCountOf(String message) {
        Matcher matcher = PACKAGE_COUNT.matcher(normalize(message));
        if (!matcher.find()) return Optional.empty();
        int count = Integer.parseInt(matcher.group(1));
        return matcher.find() ? Optional.empty() : Optional.of(count);
    }
}
