package com.storagehub.api.configuration;

public record PolicyDiffItem(
    String category,
    String fieldKey,
    String fieldLabel,
    String oldValue,
    String newValue,
    boolean changed
) {}
