package com.storagehub.api.support;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.support.ai.SupportAiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/chat")
@Tag(name = "D5 - Customer AI Chat")
@SecurityRequirement(name = "bearerAuth")
public class CustomerAiChatController {
    private final ActorContext actors;
    private final SupportAiChatService service;

    @PostMapping("/replies")
    @Operation(summary = "Ask the support assistant", description = "Active Customer only. Gemini selects a public guidance source; backend returns only approved catalog text or an unknown/staff handoff reply. No customer/business lookup, ticket or notification. UUID Idempotency-Key required. Replay is process-local for 10 minutes; client history is not sent to Gemini or used as knowledge.")
    public ApiResponse<SupportAiReplyResponse> reply(
        @Valid @RequestBody SupportAiReplyRequest body,
        @RequestHeader("Idempotency-Key") UUID key
    ) {
        return new ApiResponse<>(service.reply(actors.required(), body, key), CorrelationIdContext.current());
    }
}
