package com.storagehub.api.checkin;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record ResolveInventoryAtSiteRequest(
    @NotNull Decision decision,
    @AssertTrue(message = "Cần xác nhận khách hàng đang có mặt tại cơ sở.") boolean customerPresent,
    @Size(max = 2000) String note,
    @JsonFormat(pattern = "yyyy-MM-dd") LocalDate scheduledDate,
    @NotEmpty(message = "Cần tải ít nhất một ảnh biên bản có chữ ký khách.")
    @Size(max = 10) List<@NotNull String> evidenceReferences,
    Long resultVersion
) {
    public enum Decision {
        ACCEPT_FULL,
        ACCEPT_PARTIAL,
        CANCEL_AND_RETURN
    }
}
