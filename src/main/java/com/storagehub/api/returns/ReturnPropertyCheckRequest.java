package com.storagehub.api.returns;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReturnPropertyCheckRequest(
    @NotNull Boolean unitVacated,
    long clearanceRevision,
    @Size(max = 4000) String staffNotes,
    List<String> evidencePhotos
) {}
