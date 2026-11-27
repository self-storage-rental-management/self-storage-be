package com.storagehub.api.configuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
public record FacilityDeadlinePoliciesRequest(@NotEmpty Map<UUID, @NotNull @Valid UnitTypeDeadlinePolicy> policies) {}
