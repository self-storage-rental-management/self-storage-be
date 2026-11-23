package com.storagehub.api.maintenance;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CompleteMaintenanceTaskRequest(
    @Size(max = 4000, message = "resultReport cannot exceed 4000 characters")
    String resultReport,

    List<String> evidencePhotos,

    Boolean makeUnitAvailable,

    Boolean unitVacated,

    @Size(max = 500, message = "recoveryHandoverLocation cannot exceed 500 characters")
    String recoveryHandoverLocation,

    UUID recoveryWitnessStaffId
) {
    public CompleteMaintenanceTaskRequest(
        String resultReport,
        List<String> evidencePhotos,
        Boolean makeUnitAvailable
    ) {
        this(resultReport, evidencePhotos, makeUnitAvailable, null, null, null);
    }
}
