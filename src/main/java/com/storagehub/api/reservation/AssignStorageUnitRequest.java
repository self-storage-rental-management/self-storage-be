package com.storagehub.api.reservation;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class AssignStorageUnitRequest {

    @NotNull
    private UUID storageUnitId;

    public AssignStorageUnitRequest() {
    }

    public AssignStorageUnitRequest(UUID storageUnitId) {
        this.storageUnitId = storageUnitId;
    }

    public UUID getStorageUnitId() { return storageUnitId; }
    public void setStorageUnitId(UUID storageUnitId) { this.storageUnitId = storageUnitId; }
}
