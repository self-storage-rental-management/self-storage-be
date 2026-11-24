package com.storagehub.api.checkin;
import com.storagehub.api.reservation.InventoryResolutionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Records the arrangement agreed with the verified customer who is physically present.
 * The evidence references prove the confirmation was captured at the facility, so the
 * customer is not asked to sign in and no 48-hour confirmation clock is started.
 */
public record ResolveInventoryAtSiteRequest(
    @NotNull InventoryResolutionRequest.Decision decision,
    @NotNull Boolean customerPresent,
    @Size(max = 2000) String note,
    LocalDate scheduledDate,
    @Size(max = 20) List<@NotBlank @Size(max = 500) String> evidenceReferences,
    Long resultVersion
) {
    public ResolveInventoryAtSiteRequest(InventoryResolutionRequest.Decision decision,
        Boolean customerPresent, String note, LocalDate scheduledDate) {
        this(decision, customerPresent, note, scheduledDate, null, null);
    }

    public ResolveInventoryAtSiteRequest(InventoryResolutionRequest.Decision decision,
        Boolean customerPresent, String note, LocalDate scheduledDate,
        List<String> evidenceReferences) {
        this(decision, customerPresent, note, scheduledDate, evidenceReferences, null);
    }
}
