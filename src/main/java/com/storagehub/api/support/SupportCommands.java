package com.storagehub.api.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** Transport limits, not BO policy. Customer cannot submit priority or internal visibility. */
public final class SupportCommands {
    private SupportCommands() {}
    public enum LinkType { RENTAL, RESERVATION, PAYMENT, STORAGE_UNIT }
    public enum Visibility { PUBLIC, INTERNAL }
    public enum Module { PAYMENT, RETURN_SETTLEMENT, MAINTENANCE, HANDOVER, ACCOUNT, RENEWAL, OVERDUE }
    public enum Decision { ROUTE, REJECT }
    public record Link(@NotNull LinkType type, @NotNull UUID id) {}
    public record Create(@NotBlank @Size(max=200) String subject, @NotBlank @Size(max=4000) String description,
                         UUID facilityId, @Valid Link linkedRecord, @Size(max=10) List<@NotNull UUID> evidenceFileIds) {}
    public record Assignment(@NotNull UUID assignedStaffId, @NotNull @PositiveOrZero Long expectedVersion,
                             @NotBlank @Size(max=2000) String reason) {}
    public record Version(@NotNull @PositiveOrZero Long expectedVersion) {}
    public record CustomerMessage(@NotBlank @Size(max=4000) String body, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                                  @PositiveOrZero Long expectedVersion) {}
    public record StaffMessage(@NotBlank @Size(max=4000) String body, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                               @NotNull Visibility visibility) {}
    public record Information(@NotBlank @Size(max=4000) String message, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                              @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Resolution(@NotBlank @Size(max=4000) String summary, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                             @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Reopen(@NotBlank @Size(max=2000) String reason, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Close(@NotNull @PositiveOrZero Long expectedVersion, @Size(max=2000) String feedback) {}
    public record Escalate(@NotNull Module targetModule, @NotBlank @Size(max=2000) String reason,
                          @Size(max=10) List<@NotNull UUID> evidenceFileIds, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record EscalationDecision(@NotNull Decision action, @NotBlank @Size(max=2000) String reason,
                                     @NotNull @PositiveOrZero Long expectedVersion) {}
}
