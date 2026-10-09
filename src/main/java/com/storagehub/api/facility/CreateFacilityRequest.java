package com.storagehub.api.facility;

import com.storagehub.domain.model.FacilityStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateFacilityRequest(
    @NotBlank(message = "code is required")
    @Size(max = 32, message = "code must not exceed 32 characters")
    String code,

    @NotBlank(message = "name is required")
    @Size(max = 160, message = "name must not exceed 160 characters")
    String name,

    @NotBlank(message = "address is required")
    @Size(max = 255, message = "address must not exceed 255 characters")
    String address,

    @NotBlank(message = "city is required")
    @Size(max = 100, message = "city must not exceed 100 characters")
    String city,

    FacilityStatus status,

    List<UnitSpecRequest> unitSpecs
) {
    public record UnitSpecRequest(
        String sizeCode,
        String name,
        Integer count,
        Double monthlyPrice,
        Double lengthM,
        Double widthM,
        Double heightM,
        Double maxLoadKg
    ) {}
}
