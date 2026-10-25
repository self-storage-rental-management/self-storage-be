package com.storagehub.api.reservation;

import com.storagehub.domain.model.GoodsCatalogStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GoodsCatalogCategoryUpdateRequest(
    @NotBlank @Size(max = 160) String label,
    @NotNull GoodsCatalogStatus status
) {}
