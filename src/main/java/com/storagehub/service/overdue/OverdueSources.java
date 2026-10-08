package com.storagehub.service.overdue;

import com.storagehub.domain.model.Rental;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class OverdueSources {
    private OverdueSources() {}
    public record Obligation(UUID id,UUID rentalId,Instant dueAt,BigDecimal outstanding,String currency) {}
    public record Finance(Instant checkedAt,List<Obligation> obligations) {}
    public interface FinancialSource { Optional<Finance> read(Rental rental,Instant now); }
    public record Term(String policyRef,String policyVersion,LocalDate lastPermittedDate,
        int warningThroughDay,int seriousThroughDay,int urgentThroughDay,int recoveryFromDay,
        Instant recoveryCutoff,Instant recoveryStart) {}
    public interface TermSource { Optional<Term> term(Rental rental,Instant now); }
    public record ReminderPolicy(String reference,String version,Duration cooldown) {}
    public interface ReminderSource {
        default boolean atomic() {return false;}
        Optional<ReminderPolicy> policy(Rental rental);
        /** Owner creates a real transactional notification/outbox for the Rental customer, with delivery retries. */
        UUID enqueue(Rental rental,String caseRef,String content,String key,Instant now);
    }
    public record Handoff(UUID reference,String status) {}
    public interface RecoverySource {
        default boolean atomic() {return false;}
        /** Authorized receiver rechecks policy/assets/Return state, deduplicates and persists actual receipt. */
        Handoff receive(Rental rental,String caseRef,String reason,String key,Instant now);
    }
}
