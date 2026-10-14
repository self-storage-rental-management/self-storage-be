package com.storagehub.api.configuration;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record FacilityContentPolicy(
    @NotBlank @Size(max=100) String id,
    @NotBlank @Size(max=200) String name,
    @NotBlank @Size(max=2000) String value,
    @Size(max=500) String scope,
    boolean editable,
    @Size(max=5000) String description,
    @Size(max=30) String lastUpdated,
    @NotBlank @Pattern(regexp="all|specific") String scopeType,
    @NotNull @Size(max=1000) List<@NotNull UUID> facilityIds,
    @NotBlank @Pattern(regexp="billing|climate|prohibited|maintenance") String category
) {}
