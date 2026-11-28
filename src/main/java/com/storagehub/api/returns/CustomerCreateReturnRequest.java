package com.storagehub.api.returns;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import com.storagehub.domain.model.ReturnType;

public record CustomerCreateReturnRequest(
    @NotNull(message = "scheduledDate is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate scheduledDate,

    @jakarta.validation.constraints.NotBlank(message = "Vui lòng nhập lý do trả kho.")
    @Size(max = 2000, message = "Lý do trả kho không được quá 2000 ký tự.")
    String notes,

    /** Internal workflow marker used when inventory mismatch forces a return. */
    ReturnType returnType
) {
    public CustomerCreateReturnRequest(LocalDate scheduledDate, String notes) {
        this(scheduledDate, notes, null);
    }
}
