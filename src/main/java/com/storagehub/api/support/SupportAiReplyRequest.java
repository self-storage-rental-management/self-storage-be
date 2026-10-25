package com.storagehub.api.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SupportAiReplyRequest(
    @NotBlank @Size(max = 1000) String message,
    @Size(max = 10) List<@NotNull @Valid HistoryMessage> history
) {
    // System/developer/tool roles cannot be supplied by a Customer.
    public enum HistoryRole { user, assistant }
    public record HistoryMessage(
        @NotNull HistoryRole role,
        @NotBlank @Size(max = 1000) String content
    ) {}
}
