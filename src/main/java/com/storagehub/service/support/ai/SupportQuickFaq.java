package com.storagehub.service.support.ai;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Exact matches and Gemini selection resolve to the same public catalog. */
@Component @RequiredArgsConstructor
public class SupportQuickFaq {
    private final SupportPublicKnowledge knowledge;
    public Optional<String> answer(String question){return knowledge.exact(question).map(SupportPublicKnowledge.Entry::answer);}
}
