package com.storagehub.service.support.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Deployment-reviewed public guidance. Never loads business settings or customer records. */
@Component
public class SupportPublicKnowledge {
    private static final Set<String> REPLY_FIELDS = Set.of("action", "sourceIds", "text", "facility", "unitType", "startDate", "endDate");
    private static final Pattern UNAPPROVED_PROSE = Pattern.compile(
        "(?iu)[\\p{N}<>@\\[\\]]|https?\\b|www\\.|/api/|\\b[a-z0-9-]+\\.(?:com|net|org|vn|io|test)\\b|\\p{Cf}");
    private static final int MAX_CANDIDATES = 6;
    private static final int MAX_SOURCES = 3;
    private static final Map<String, List<String>> SEARCH_PHRASES = Map.ofEntries(
        Map.entry("faq-unit-capacity", List.of("kich thuoc", "suc chua", "bao nhieu met", "dien tich", "co kho nao", "con kho", "loai kho", "do dac", "chon kho")),
        Map.entry("faq-contract-renew", List.of("gia han", "thue tiep", "keo dai", "them vai thang", "tiep tuc thue", "ky tiep")),
        Map.entry("faq-renewal-dates", List.of("ky moi", "tinh tu ngay", "bat dau", "noi tiep", "ngay setup", "ngay chuan bi", "het han roi", "tu hom nao")),
        Map.entry("faq-payment-method", List.of("thanh toan", "giao dich", "tra tien", "dong tien", "chuyen khoan", "bien lai", "quet ma", "vnpay")),
        Map.entry("faq-late-fee", List.of("qua han", "tre han", "dong tre", "tra cham", "phi phat", "khoan no")),
        Map.entry("faq-return-storage", List.of("tra kho", "hoan tien", "tien coc", "tra lai gian", "thanh ly", "ket thuc thue", "tien dam bao", "don do ra")),
        Map.entry("faq-password-reset", List.of("mat khau", "quen pass", "doi pass", "khong nho pass")),
        Map.entry("faq-pin-reset", List.of("ma pin", "ma truy cap", "ban phim", "ma mo cua", "nhap ma")),
        Map.entry("faq-gate-stuck", List.of("cua kho", "cong", "cua cuon", "ket cua", "khong mo", "cua bi ket")),
        Map.entry("faq-switch-unit", List.of("doi kho", "doi sang", "chuyen kho", "chuyen sang", "gian rong hon", "gian lon hon", "gian nho hon")),
        Map.entry("faq-damage-leak", List.of("hu hong", "bi hu", "thiet hai", "tham dot", "bi dot", "bi uot", "nuoc ro", "boi thuong", "bao hiem"))
    );
    public static final String VERSION="support-public/2026-10-26-v2";
    public static final String UNKNOWN="Mình không biết câu trả lời từ nguồn công khai đã được xác nhận của StorageHub. Bạn có thể chọn Nhân viên để được hỗ trợ hoặc kiểm tra trường hợp cụ thể.";
    public record Reference(String label,String path) {}
    public record Entry(String id,String question,List<String> aliases,String answer,List<Reference> sources) {}
    public record Catalog(String version,List<Entry> entries) {}
    private final Catalog catalog;
    public SupportPublicKnowledge(ObjectMapper mapper) throws IOException {
        var raw=new ClassPathResource("ai/support-public-knowledge.json").getContentAsString(StandardCharsets.UTF_8);
        catalog=mapper.readValue(raw,Catalog.class);
        if(!VERSION.equals(catalog.version())||catalog.entries()==null||catalog.entries().isEmpty()
            ||catalog.entries().stream().map(Entry::id).distinct().count()!=catalog.entries().size()
            ||catalog.entries().stream().anyMatch(e->e.id()==null||!e.id().matches("faq-[a-z-]+")||e.question()==null||e.answer()==null||e.answer().isBlank()
                ||e.aliases()==null||e.sources()==null||e.sources().isEmpty()||e.sources().stream().anyMatch(s->s.label()==null||s.path()==null
                    ||!s.path().matches("/api/(public|customer|auth)/[a-zA-Z0-9/-]+"))))
            throw new IOException("Invalid public support knowledge catalog");
    }
    public Catalog catalog(){return catalog;}
    public Optional<Entry> find(String id){return catalog.entries().stream().filter(e->e.id().equals(id)).findFirst();}
    public Optional<Entry> exact(String question){
        String normalized=normalize(question);
        return catalog.entries().stream().filter(e->normalize(e.question()).equals(normalized)
            ||e.aliases().stream().anyMatch(a->normalize(a).equals(normalized))).findFirst();
    }
    List<Entry> candidates(String question) {
        String normalized = normalize(question);
        return catalog.entries().stream()
            .map(entry -> new Match(entry, SEARCH_PHRASES.getOrDefault(entry.id(), List.of()).stream()
                .filter(phrase -> containsPhrase(normalized, phrase)).count()))
            .filter(match -> match.score() > 0)
            .sorted(Comparator.comparingLong(Match::score).reversed())
            .limit(MAX_CANDIDATES)
            .map(Match::entry)
            .toList();
    }

    SupportAiProvider.Answer parseReply(String providerBody, ObjectMapper mapper, String model) {
        var handoff = new SupportAiProvider.Answer(List.of(), model, false);
        try {
            JsonNode reply = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(providerBody);
            if (reply == null || !reply.isObject() || !reply.path("action").isTextual()) return handoff;
            for (var fields = reply.fieldNames(); fields.hasNext();) {
                if (!REPLY_FIELDS.contains(fields.next())) return handoff;
            }
            var action = SupportAiProvider.Action.valueOf(reply.path("action").textValue());
            JsonNode ids = reply.path("sourceIds");
            if (!ids.isArray() || ids.size() > MAX_SOURCES) return handoff;
            var allowed = catalog.entries().stream().map(Entry::id).collect(Collectors.toSet());
            var selected = new ArrayList<String>();
            for (JsonNode id : ids) {
                if (!id.isTextual() || !allowed.remove(id.textValue())) return handoff;
                selected.add(id.textValue());
            }
            if (action == SupportAiProvider.Action.ANSWER || action == SupportAiProvider.Action.CLARIFY) {
                return selected.isEmpty() ? handoff
                    : new SupportAiProvider.Answer(List.copyOf(selected), model, false, action, limitedText(reply, "text", 1000), null);
            }
            if (!selected.isEmpty()) return handoff;
            var lookup = switch (action) {
                case HOURS, PHONE, CONTACT, PRICE, AVAILABILITY -> new SupportAiProvider.Lookup(
                    limitedText(reply, "facility", 100), limitedText(reply, "unitType", 30),
                    limitedText(reply, "startDate", 10), limitedText(reply, "endDate", 10));
                default -> null;
            };
            return new SupportAiProvider.Answer(List.of(), model, false, action, null, lookup);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return handoff;
        }
    }

    private static String limitedText(JsonNode reply, String field, int max) {
        String value = reply.path(field).textValue();
        if (value == null || value.isBlank() || value.length() > max) return null;
        return value.strip();
    }

    String display(SupportAiProvider.Answer answer) {
        String fallback = resolve(answer.sourceIds());
        String prose = answer.text();
        if (fallback.equals(UNKNOWN) || prose == null || UNAPPROVED_PROSE.matcher(prose).find()) return fallback;
        String labels = answer.sourceIds().stream().map(id -> find(id).orElseThrow())
            .flatMap(entry -> entry.sources().stream()).map(Reference::label).distinct().collect(Collectors.joining(" · "));
        return prose + "\n\n**Nguồn hướng dẫn** · " + labels;
    }

    String resolve(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_SOURCES || new HashSet<>(ids).size() != ids.size()) {
            return UNKNOWN;
        }
        var entries = new ArrayList<Entry>();
        for (String id : ids) {
            var entry = find(id);
            if (entry.isEmpty()) return UNKNOWN;
            entries.add(entry.get());
        }
        String answer = entries.stream().map(Entry::answer).collect(Collectors.joining("\n\n"));
        if (entries.size() == 1) return answer;
        String sources = entries.stream().flatMap(entry -> entry.sources().stream()).map(Reference::label)
            .distinct().collect(Collectors.joining(" · "));
        return answer + "\n\n**Nguồn hướng dẫn** · " + sources;
    }

    private record Match(Entry entry, long score) {}

    static boolean containsPhrase(String text, String phrase) {
        return !phrase.isBlank() && (" " + text + " ").contains(" " + phrase + " ");
    }

    static String normalize(String text){
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .replace('đ','d').replaceAll("[^a-z0-9\\s]"," ").replaceAll("\\s+"," ").trim();
    }
}
