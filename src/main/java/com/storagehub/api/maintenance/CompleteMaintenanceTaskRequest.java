package com.storagehub.api.maintenance;

import jakarta.validation.constraints.Size;
import java.util.List;

public record CompleteMaintenanceTaskRequest(
    @Size(max = 4000, message = "resultReport cannot exceed 4000 characters")
    String resultReport,

    List<String> evidencePhotos,

    Boolean makeUnitAvailable
) {}
