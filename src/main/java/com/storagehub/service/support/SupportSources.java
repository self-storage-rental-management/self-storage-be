package com.storagehub.service.support;

import com.storagehub.api.support.SupportCommands.Module;
import com.storagehub.api.support.SupportCommands.Visibility;
import com.storagehub.domain.model.SupportTicket;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.*;

/** Extension points for shared owners. No runtime test/fake policy adapters are installed. */
public final class SupportSources {
    private SupportSources() {}
    public interface EvidenceSource {
        /** Must bind validated ownership/visibility in the ticket transaction (or a durable outbox). */
        default boolean atomic() {return false;}
        /** Unsupported legacy namespaces remain unavailable, not falsely certified by a new adapter. */
        default boolean supportsFiles(List<UUID> files) {return true;}
        /** Shared file owner validates AND binds attach/read visibility. No arbitrary URLs. */
        void requireAttach(ActorPrincipal actor,SupportTicket ticket,Visibility visibility,List<UUID> files);
        void requireRead(ActorPrincipal actor,SupportTicket ticket,Visibility visibility,List<UUID> files);
    }
    public record Sla(String priority,String policyRef,String policyVersion,Instant firstReplyDueAt,
                      Instant nextUpdateDueAt,boolean activeWorkPaused) {}
    public interface SlaSource {
        /** Real BO policy/calendar; missing != within SLA. No priority chosen by Customer. */
        Optional<Sla> read(SupportTicket ticket,SupportState state,Instant now);
    }
    public record CloseRule(String policyRef,String policyVersion,boolean customerMayClose,Instant autoCloseAt) {}
    public interface ClosePolicySource {
        Optional<CloseRule> read(SupportTicket ticket,SupportState state,Instant now);
    }
    /** Verified delivery of the resolution/review notice, not a queued or merely persisted notification. */
    public record ReviewNotice(UUID reference,UUID ticketId,UUID customerId,UUID resolutionEventId,
                               String policyRef,String policyVersion,Instant deliveredAt) {}
    public interface NotificationSource {
        /** Owner guarantees proof is authoritative for this resolution cycle until close commits. */
        default boolean consistentThroughClose() {return false;}
        /** Read only. Bind recipient, ticket, current RESOLVED event and published policy; never send here. */
        Optional<ReviewNotice> reviewNotice(SupportTicket ticket,UUID resolutionEventId,CloseRule rule,Instant now);
    }
    public interface ResolutionSource {
        /** Objective-specific verified result; a note is not proof of refund/payment/maintenance completion. */
        void requireResult(SupportTicket ticket,SupportState state);
    }
    public record ReceiverResult(UUID ticketId,UUID escalationId,UUID receiverRef,String status,UUID resultRef) {}
    public interface EscalationSource {
        default boolean atomic() {return false;}
        boolean supports(Module module);
        /** Same transaction or durable outbox. Reference means queued, NOT a successful receiver result. */
        UUID route(SupportTicket ticket,SupportEscalation escalation,UUID manager,String key,Instant now);
        Optional<ReceiverResult> result(SupportTicket ticket,SupportEscalation escalation);
    }
}
