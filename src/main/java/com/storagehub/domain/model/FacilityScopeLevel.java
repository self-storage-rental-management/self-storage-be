package com.storagehub.domain.model;

public enum FacilityScopeLevel {
    READ,
    OPERATE,
    MANAGE;

    public boolean includes(FacilityScopeLevel required) {
        return ordinal() >= required.ordinal();
    }
}
