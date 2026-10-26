package com.storagehub.domain.model;

public enum StorageUnitStatus {
    available,
    reserved,
    occupied,
    maintenance,
    held,
    assigned,
    inactive,
    archived;

    public static java.util.List<StorageUnitStatus> operationalStatuses() {
        return java.util.List.of(available, reserved, occupied, maintenance, held, assigned);
    }
}
