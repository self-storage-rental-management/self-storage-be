package com.storagehub.api.support;

import com.storagehub.common.api.*;
import com.storagehub.service.support.ai.SupportPublicKnowledge;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class PublicSupportKnowledgeController {
    private final SupportPublicKnowledge knowledge;
    @GetMapping("/api/public/support/knowledge")
    public ApiResponse<SupportPublicKnowledge.Catalog> read(){
        return new ApiResponse<>(knowledge.catalog(),CorrelationIdContext.current());
    }
}
