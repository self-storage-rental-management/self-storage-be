package com.storagehub.api.credential;

import jakarta.validation.constraints.Size;
import java.time.Instant;

/** A physical access attempt recorded by Staff while a credential is in use. */
public record RecordCredentialAccessRequest(
    Instant accessedAt,
    @Size(max = 120) String source,
    @Size(max = 500) String note
) {}
