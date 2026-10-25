package com.storagehub.api.support;

import java.time.Instant;
import java.util.UUID;

public record SupportAiReplyResponse(
    UUID requestId,
    String authorRole,
    String source,
    String answer,
    String model,
    String knowledgeVersion,
    Instant generatedAt,
    boolean truncated
) {}
