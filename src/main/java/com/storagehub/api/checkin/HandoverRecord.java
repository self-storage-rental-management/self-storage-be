package com.storagehub.api.checkin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HandoverRecord(String facilityName, String facilityAddress, UUID handedOverBy,
    String handedOverByName, Instant handedOverAt, List<Item> items, String signedRecordReference,
    String note, Integer rackCount, Integer palletCount) {
    public HandoverRecord(String facilityName, String facilityAddress, UUID handedOverBy,
        String handedOverByName, Instant handedOverAt, List<Item> items, String signedRecordReference, String note) {
        this(facilityName, facilityAddress, handedOverBy, handedOverByName, handedOverAt, items, signedRecordReference, note, null, null);
    }
    public record Item(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String name,
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) int quantity) {}
}
