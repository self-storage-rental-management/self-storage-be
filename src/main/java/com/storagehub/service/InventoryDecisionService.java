package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.InventoryDecisionResponse;
import com.storagehub.api.reservation.InventoryResolutionRequest;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryDecisionService {
    private final ObjectMapper objectMapper;
    private final CheckInRepository checkIns;
    private final ReturnCaseRepository returns;

    public void record(CheckIn checkIn, InventoryResolutionRequest.Decision decision,
        User operator, boolean atSite, List<UUID> evidence) {
        String outcome = switch (decision) {
            case ACCEPT_FULL -> "ACCEPTED";
            case REQUEST_TRANSFER -> "TRANSFER_REQUESTED";
            case CANCEL_AND_RETURN -> atSite ? "REFUSED" : "RETURN_REQUESTED";
            default -> throw new IllegalArgumentException("Unsupported intake decision");
        };
        save(checkIn, operator, outcome, atSite ? "STAFF" : "CUSTOMER", evidence);
    }

    public void recordManagerRefusal(CheckIn checkIn, User manager, UUID evidenceId) {
        save(checkIn, manager, "REFUSED", "MANAGER", List.of(evidenceId));
    }

    private void save(CheckIn checkIn, User operator, String outcome, String source, List<UUID> evidence) {
        var linked = "REFUSED".equals(outcome) || "RETURN_REQUESTED".equals(outcome)
            ? returns.findByRental_Id(checkIn.getRental().getId()).stream()
                .max(java.util.Comparator.comparing(com.storagehub.domain.model.ReturnCase::getRequestedAt,
                    java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))).orElse(null)
            : null;
        var snapshot = new InventoryDecisionResponse(outcome, source,
            operator.getId(), operator.getFullName(), checkIn.getInventoryDecisionAt(),
            checkIn.getInventoryDecisionNote(), evidence == null ? List.of() : List.copyOf(evidence),
            linked == null ? null : linked.getId(), linked == null ? null : linked.getStatus().name(),
            linked == null ? null : linked.getScheduledDate());
        try {
            checkIn.setInventoryDecisionJson(objectMapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store inventory intake decision", ex);
        }
        checkIns.saveAndFlush(checkIn);
    }

    public InventoryDecisionResponse read(CheckIn checkIn) {
        if (checkIn == null || checkIn.getInventoryDecisionJson() == null) return null;
        try {
            var saved = objectMapper.readValue(checkIn.getInventoryDecisionJson(), InventoryDecisionResponse.class);
            var linked = saved.returnCaseId() == null ? null : returns.findById(saved.returnCaseId()).orElse(null);
            return new InventoryDecisionResponse(saved.outcome(), saved.source(), saved.decidedBy(),
                saved.decidedByName(), saved.decidedAt(), saved.note(), saved.evidenceReferences(),
                saved.returnCaseId(), linked == null ? saved.returnStatus() : linked.getStatus().name(),
                linked == null ? saved.scheduledReturnDate() : linked.getScheduledDate());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read inventory intake decision", ex);
        }
    }
}
