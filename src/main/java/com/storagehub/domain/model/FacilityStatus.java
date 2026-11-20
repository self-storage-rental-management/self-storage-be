package com.storagehub.domain.model;

public enum FacilityStatus {
    setup,
    active,
    maintenance,
    inactive;

    public boolean allowsRenewalSigning() {
        return this == active || this == maintenance;
    }
}
