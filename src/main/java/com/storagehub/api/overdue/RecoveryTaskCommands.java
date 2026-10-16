package com.storagehub.api.overdue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.storagehub.api.returns.ReturnInspectionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class RecoveryTaskCommands {
    private RecoveryTaskCommands() {}
    public record Start(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=2000) String note) {
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unsupported Recovery field: "+key);}
    }
    public record Inspection(@NotNull @PositiveOrZero Long expectedVersion,@NotNull @Valid ReturnInspectionRequest inspection,
        @NotEmpty @Size(max=10) List<@NotNull UUID> evidenceFileIds) {
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unsupported Recovery field: "+key);}
    }
    public enum PaymentMethod { CASH, BANK_TRANSFER }
    public record Settlement(@NotNull @PositiveOrZero Long expectedVersion,@NotNull PaymentMethod paymentMethod,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}") String transactionReference,
        @NotNull UUID receiptFileId,@NotNull @AssertTrue Boolean confirmed) {
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unsupported Recovery field: "+key);}
    }
    public record Completion(@NotNull @PositiveOrZero Long expectedVersion,@NotNull @AssertTrue Boolean assetsCleared,
        @NotNull @AssertTrue Boolean unitInspected,@NotNull UUID reportFileId,@NotBlank @Size(max=2000) String note) {
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unsupported Recovery field: "+key);}
    }
}
