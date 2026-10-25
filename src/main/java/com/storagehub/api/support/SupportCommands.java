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
    public enum ContextRole { CUSTOMER, ASSISTANT }
    /** Customer-supplied context, never an authenticated Staff or provider message. */
    public record HandoffMessage(@NotNull ContextRole role, @NotBlank @Size(max=4000) String body) {}
    public record Create(@NotBlank @Size(max=200) String subject, @NotBlank @Size(max=4000) String description,
                         UUID facilityId, @Valid Link linkedRecord, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         @Size(max=200) List<@NotNull @Valid HandoffMessage> handoffMessages) {
        public Create(String subject,String description,UUID facilityId,Link linkedRecord,List<UUID> evidenceFileIds) {
            this(subject,description,facilityId,linkedRecord,evidenceFileIds,null);
        }
    }
    public record Assignment(@NotNull UUID assignedStaffId, @NotNull @PositiveOrZero Long expectedVersion,
                             @NotBlank @Size(max=2000) String reason) {}
    public record Version(@NotNull @PositiveOrZero Long expectedVersion) {}
    public record CustomerMessage(@NotNull @Size(max=4000) String body, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                                  @PositiveOrZero Long expectedVersion) {}
    public record StaffMessage(@NotNull @Size(max=4000) String body, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                               @NotNull Visibility visibility) {}
    public record Information(@NotBlank @Size(max=4000) String message, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                              @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Resolution(@Size(max=4000) String summary, @Size(max=10) List<@NotNull UUID> evidenceFileIds,
                             @NotNull @PositiveOrZero Long expectedVersion,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             Boolean statusOnly) {
        public Resolution(String summary,List<UUID> evidenceFileIds,Long expectedVersion) { this(summary,evidenceFileIds,expectedVersion,null); }
    }
    public record Reopen(@NotBlank @Size(max=2000) String reason, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Close(@NotNull @PositiveOrZero Long expectedVersion, @Size(max=2000) String feedback) {}
    public record ReadMessages(@NotNull @Size(min=1,max=100) List<@NotNull UUID> messageIds) {}
    public record Escalate(@NotNull Module targetModule, @NotBlank @Size(max=2000) String reason,
                          @Size(max=10) List<@NotNull UUID> evidenceFileIds, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record EscalationDecision(@NotNull Decision action, @NotBlank @Size(max=2000) String reason,
                                     @NotNull @PositiveOrZero Long expectedVersion) {}
}
