package com.storagehub.service.rental.period;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.domain.model.*;
import com.storagehub.service.RentalReadSources;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Scoped, read-only proof adapter. Legacy, reused and ambiguous records remain UNKNOWN. */
@Component @RequiredArgsConstructor @Transactional(readOnly = true)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "storagehub.integration.rental-period.enabled", havingValue = "true", matchIfMissing = false)
public class ProjectHandoffRentalPeriodAdapter implements RentalReadSources.DateSource {
    private final EntityManager em;
    private final ObjectMapper mapper;
    /** Current project rule, explicitly enabled locally; historical evidence is not rewritten. */
    @org.springframework.beans.factory.annotation.Value("${storagehub.integration.rental-period.inclusive-end:false}")
    private boolean inclusiveEnd;

    public Optional<RentalReadSources.Dates> read(Rental r) {
        if (!linked(r)) return Optional.empty();
        var handovers = em.createQuery("select c from CheckIn c where c.reservation.id=:id", CheckIn.class)
            .setParameter("id", r.getReservation().getId()).setMaxResults(2).getResultList();
        if (handovers.size() != 1 || !same(r.getReservation().getId(), handovers.getFirst().getReservation())
            || handovers.getFirst().getStatus() != CheckInStatus.completed
            || !same(r.getId(), handovers.getFirst().getRental())) return Optional.empty();
        var completions = em.createQuery("select e from RenewalOperationEvent e where e.renewal.rental.id=:id and e.kind='COMPLETION' order by e.occurredAt desc,e.id desc", RenewalOperationEvent.class)
            .setParameter("id", r.getId()).setMaxResults(2).getResultList();
        Optional<RentalPeriod> period;
        if (!completions.isEmpty()) {
            if (completions.size() > 1 && Objects.equals(completions.get(0).getOccurredAt(), completions.get(1).getOccurredAt())) return Optional.empty();
            period = completed(r, completions.getFirst());
        } else {
            var completed = em.createQuery("select n.id from Renewal n where n.rental.id=:id and n.status=:status", UUID.class)
                .setParameter("id", r.getId()).setParameter("status", RenewalStatus.completed).setMaxResults(1).getResultList();
            if (!completed.isEmpty()) return Optional.empty();
            period = receipt(r);
        }
        if (inclusiveEnd) period = period.flatMap(p -> RentalPeriod.verified(p.rentalId(), p.startDate(),
            p.storedEndDate(), RentalPeriod.Convention.INCLUSIVE, "project-inclusive-end:v1:" + p.reference()));
        return period.map(p -> new RentalReadSources.Dates(p.rentalId(), p.startDate(), p.lastPermittedDate(),
            p.reference(), p.storedEndDate(), p.convention()));
    }

    private Optional<RentalPeriod> receipt(Rental r) {
        var rows = em.createQuery("select a from ActivityLog a where a.entityType='Reservation' and a.entityId=:id and a.action='CUSTOMER_RECEIPT_CONFIRMED' order by a.createdAt desc,a.id desc", ActivityLog.class)
            .setParameter("id", r.getReservation().getId()).setMaxResults(2).getResultList();
        if (rows.size() != 1) return Optional.empty();
        var log = rows.getFirst();
        if (log.getId() == null || !Objects.equals(r.getReservation().getId(), log.getEntityId())
            || !"Reservation".equals(log.getEntityType()) || !"CUSTOMER_RECEIPT_CONFIRMED".equals(log.getAction())
            || !same(r.getCustomer().getId(), log.getActor())
            || !same(r.getFacility().getId(), log.getFacility())) return Optional.empty();
        var root = json(log.getAfterStateJson());
        var d = root.path("rentalPeriodEvidence");
        // Approved current convention may apply to a legacy confirmed handover without versioned metadata.
        // It establishes today's rule, not a fabricated historical audit or a backfilled DB date.
        if (inclusiveEnd && d.isMissingNode() && "COMPLETED".equals(root.path("status").asText())
            && id(root, "rentalId", r.getId())
            && Objects.equals(r.getStartDate(), r.getReservation().getStartDate())
            && Objects.equals(r.getContractEndDate(), r.getReservation().getEndDate()))
            return RentalPeriod.verified(r.getId(), r.getStartDate(), r.getContractEndDate(),
                RentalPeriod.Convention.INCLUSIVE, "legacy-receipt:" + log.getId());
        if (!"COMPLETED".equals(root.path("status").asText()) || !id(root, "rentalId", r.getId())
            || !"CUSTOMER_RECEIPT_PERIOD_V1".equals(d.path("schema").asText())
            || !d.path("rentalCreated").isBoolean() || !d.path("rentalCreated").booleanValue()
            || !"EXCLUSIVE".equals(d.path("convention").asText())
            || !Objects.equals(r.getStartDate(), r.getReservation().getStartDate())
            || !Objects.equals(r.getContractEndDate(), r.getReservation().getEndDate())) return Optional.empty();
        return evidence(r, d, "receipt:" + log.getId());
    }

    private Optional<RentalPeriod> completed(Rental r, RenewalOperationEvent event) {
        var n = event.getRenewal();
        var state = em.find(RenewalOperationState.class, n.getId());
        var wf = em.find(RenewalWorkflow.class, n.getId());
        if (event.getId() == null || n.getStatus() != RenewalStatus.completed || !same(r.getId(), n.getRental())
            || state == null || state.getPhase() != RenewalOperationState.Phase.COMPLETED || wf == null
            || !Objects.equals(state.getCompletedAt(), event.getOccurredAt())
            || !Objects.equals(state.getCompletedBy(), event.getActorId()) || !same(r.getCustomer().getId(), n.getRequestedBy())) return Optional.empty();
        var d = json(event.getPayloadJson()).path("rentalPeriodEvidence");
        var rev = wf.getAcceptedRevision();
        if (rev == null || rev.getQuote() == null || !same(n.getId(), rev.getRenewal()) || !same(r.getId(), rev.getQuote().getRental())
            || !same(r.getCustomer().getId(), rev.getQuote().getCustomer())
            || !"RENEWAL_COMPLETION_PERIOD_V1".equals(d.path("schema").asText())
            || d.path("previousProof").asText().isBlank() || !id(d, "acceptedQuoteId", rev.getQuote().getId())) return Optional.empty();
        try {
            var t = mapper.readValue(rev.getQuote().getTermsJson(), RenewalQuoteResponse.Terms.class);
            if (t == null) return Optional.empty();
            var p = evidence(r, d, "completion:" + event.getId());
            if (p.isEmpty() || !Objects.equals(n.getNewEndDate(), p.get().lastPermittedDate())
                || !Objects.equals(t.newEndDate(), p.get().lastPermittedDate())
                || !Objects.equals(t.oldEndDate(), date(d, "oldStoredEndDate"))
                || !Objects.equals(t.extensionEndExclusive(), p.get().endExclusive())
                || !Objects.equals(t.storageUnitId(), r.getStorageUnit().getId())
                || !Objects.equals(t.facilityId(), r.getFacility().getId())) return Optional.empty();
            var previous = RentalPeriod.verified(r.getId(), r.getStartDate(), t.oldEndDate(),
                p.get().convention(), d.path("previousProof").asText());
            if (previous.isEmpty() || !Objects.equals(previous.get().endExclusive(), t.extensionStartDate())
                || t.rentalMonths() < 1 || !previous.get().endExclusive().plusMonths(t.rentalMonths()).equals(p.get().endExclusive()))
                return Optional.empty();
            RenewalPeriodCompatibility.require(previous.get(), t);
            return p;
        } catch (JsonProcessingException | DateTimeException | com.storagehub.common.api.ApiException e) { return Optional.empty(); }
    }

    private Optional<RentalPeriod> evidence(Rental r, JsonNode d, String ref) {
        if (!id(d, "rentalId", r.getId()) || !id(d, "reservationId", r.getReservation().getId())
            || !id(d, "customerId", r.getCustomer().getId()) || !id(d, "facilityId", r.getFacility().getId())
            || !id(d, "storageUnitId", r.getStorageUnit().getId())) return Optional.empty();
        try {
            return RentalPeriod.verified(r.getId(), date(d, "startDate"), date(d, "storedEndDate"),
                RentalPeriod.Convention.valueOf(d.path("convention").asText()), ref).filter(p -> p.matches(r));
        } catch (IllegalArgumentException e) { return Optional.empty(); }
    }
    private boolean linked(Rental r) {
        if (r == null || r.getId() == null || r.getStartDate() == null || r.getContractEndDate() == null
            || r.getCustomer() == null || r.getFacility() == null || r.getStorageUnit() == null
            || r.getReservation() == null || r.getReservation().getStatus() != ReservationStatus.COMPLETED) return false;
        var res = r.getReservation();
        return same(r.getCustomer().getId(), res.getCustomer()) && same(r.getFacility().getId(), res.getFacility())
            && same(r.getFacility().getId(), r.getStorageUnit().getFacility())
            && same(r.getStorageUnit().getId(), res.getAssignedUnit());
    }
    private JsonNode json(String value) {
        try { var node = value == null ? null : mapper.readTree(value); return node == null ? mapper.createObjectNode() : node; }
        catch (JsonProcessingException e) { return mapper.createObjectNode(); }
    }
    private LocalDate date(JsonNode d, String key) {
        try { return LocalDate.parse(d.path(key).asText()); }
        catch (DateTimeException e) { return null; }
    }
    private boolean id(JsonNode d, String key, UUID id) { return id != null && id.toString().equals(d.path(key).asText()); }
    private boolean same(UUID id, BaseEntity e) { return id != null && e != null && id.equals(e.getId()); }
}
