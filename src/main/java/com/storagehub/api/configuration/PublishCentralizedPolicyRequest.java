package com.storagehub.api.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record PublishCentralizedPolicyRequest(
    @NotNull(message = "Ngày bắt đầu hiệu lực (effectiveFrom) là bắt buộc")
    LocalDate effectiveFrom,

    LocalDate effectiveTo,

    @NotBlank(message = "Tóm tắt thay đổi chính sách (changeSummary) là bắt buộc trước khi ban hành")
    String changeSummary
) {}
