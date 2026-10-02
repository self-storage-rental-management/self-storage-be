package com.storagehub.api.returns;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CustomerCreateReturnRequest(
    @NotNull(message = "scheduledDate is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate scheduledDate,

    @Size(max = 2000, message = "notes cannot exceed 2000 characters")
    String notes
) {}
