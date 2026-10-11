package com.storagehub.api.renewal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class RenewalOperationCommands {
    private RenewalOperationCommands() {}
    private static void reject(String key) {throw new IllegalArgumentException("Unknown operation field: "+key);}
    public record Version(@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record Appointment(@NotNull Instant appointmentAt,@Size(max=2000) String reason,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record Arrival(@NotNull UUID appointmentRef,@NotNull @Size(max=10) List<@NotNull UUID> evidenceFileIds,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record Incident(@NotNull UUID appointmentRef,@NotBlank @Size(max=2000) String reason,@NotNull @Size(max=10) List<@NotNull UUID> evidenceFileIds,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record Cash(@NotNull UUID payableStatementRef,@NotBlank @Size(max=100) String receiptReference,@NotNull @AssertTrue Boolean received,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record Completion(@NotNull @PositiveOrZero Long expectedVersion,@NotNull @AssertTrue Boolean identityVerified,@NotNull UUID arrivalRef,@NotNull UUID signedDocumentFileId,@Size(max=2000) String completionNote) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public enum ExceptionAction { APPROVE_RESCHEDULE_BEFORE_CUTOFF, REQUEST_POST_CUTOFF_REVIEW, REJECT }
    public record ExceptionDecision(@NotNull UUID incidentId,@NotNull ExceptionAction action,Instant appointmentAt,Instant revisedDeadline,@NotBlank @Size(max=2000) String reason,@NotNull @Size(max=10) List<@NotNull UUID> evidenceFileIds,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record Confirmation(@NotNull UUID decisionRef,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record RefundDecision(@NotNull UUID incidentId,@NotNull RenewalCommands.DecisionType decision,@NotBlank @Size(max=2000) String reason,@NotNull @Size(max=10) List<@NotNull UUID> evidenceFileIds,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record StaffAssignment(@NotNull UUID assignedStaffId,@NotBlank @Size(max=2000) String reason,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
    public record FaultReview(@NotNull UUID incidentId,@NotNull Boolean facilityFault,@NotBlank @Size(max=2000) String reason,@NotNull @Size(max=10) List<@NotNull UUID> evidenceFileIds,@NotNull @PositiveOrZero Long expectedVersion) { @JsonAnySetter public void unknown(String k,Object v){reject(k);} }
}
