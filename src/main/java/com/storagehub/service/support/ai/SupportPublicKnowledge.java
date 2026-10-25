package com.storagehub.service.support.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Deployment-reviewed public guidance. Never loads business settings or customer records. */
@Component
public class SupportPublicKnowledge {
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
    public String approved(String answer){return catalog.entries().stream().anyMatch(e->e.answer().equals(answer))?answer:UNKNOWN;}
    public Optional<Entry> exact(String question){
        String normalized=normalize(question);
        return catalog.entries().stream().filter(e->normalize(e.question()).equals(normalized)
            ||e.aliases().stream().anyMatch(a->normalize(a).equals(normalized))).findFirst();
    }
    public String selectionContext(ObjectMapper mapper) throws IOException {return mapper.writeValueAsString(catalog);}
    public String grounded(String providerSelection,ObjectMapper mapper){
        try {
            var selection=mapper.readTree(providerSelection);
            if(!selection.isObject()||selection.size()!=1||!selection.path("sourceId").isTextual())return UNKNOWN;
            return find(selection.path("sourceId").asText()).map(Entry::answer).orElse(UNKNOWN);
        } catch(Exception invalid){return UNKNOWN;}
    }
    private static String normalize(String text){
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .replace('đ','d').replaceAll("[^a-z0-9\\s]"," ").replaceAll("\\s+"," ").trim();
    }
}
