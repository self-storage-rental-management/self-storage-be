package com.storagehub.api.checkin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import com.storagehub.api.checkin.HandoverRecord.Item;

public record StartCheckInSetupRequest(
    @NotBlank @Size(max = 160) String lockReference,
    @Size(max = 500) String note,
    @Size(max = 1000) String initialUnitCondition,
    @Size(max = 10) List<@NotBlank @Size(max = 500) String> evidenceReferences,
    @jakarta.validation.Valid @Size(max = 10) List<@jakarta.validation.constraints.NotNull Item> handedOverItems,
    @Size(max = 500) String signedRecordReference,
    @Min(0) @Max(1000) Integer rackCount,
    @Min(0) @Max(10000) Integer palletCount
) {
    public StartCheckInSetupRequest(String lockReference, String note, String initialUnitCondition,
        List<String> evidenceReferences, List<Item> handedOverItems, String signedRecordReference) {
        this(lockReference, note, initialUnitCondition, evidenceReferences, handedOverItems, signedRecordReference, null, null);
    }
    public StartCheckInSetupRequest(String lockReference, String note, String initialUnitCondition, List<String> evidenceReferences) {
        this(lockReference, note, initialUnitCondition, evidenceReferences, null, null, null, null);
    }
    public StartCheckInSetupRequest(String lockReference, String note) {
        this(lockReference, note, null, null);
    }
}
