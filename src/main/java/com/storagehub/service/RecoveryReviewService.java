package com.storagehub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Customer identity documents and raw workflow JSON are excluded from the review projection. */
@Service
@RequiredArgsConstructor
public class RecoveryReviewService {
    private final CheckInRepository checkIns;
    private final ReturnCaseRepository returns;
    private final FileAssetRepository files;
    private final ObjectMapper mapper;

    public record Evidence(UUID id, String name, String contentType) {}
    public record Goods(String name, int quantity) {}
    public record Report(boolean available, String recordedBy, Instant publishedAt, String mismatchSummary,
        String decisionNote, String notes, String goodsCondition, String unitCondition,
        Integer packageCount, List<Goods> goods, List<Evidence> evidence) {}
    public record Review(Report report, Instant removalDeadlineAt, String remediationStatus,
        Instant remediationAt, String remediationNote, boolean openingAllowed, String blockingReason) {}

    public Review read(ViolationRecovery recovery, Instant now) {
        var rental = recovery.getRental();
        var checkIn = checkIns.findByReservation_Id(rental.getReservation().getId()).orElse(null);
        var returned = recovery.getSourceReturnId() == null
            ? returns.findByRental_Id(rental.getId()).stream()
                .max(Comparator.comparing(ReturnCase::getRequestedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null)
            : returns.findById(recovery.getSourceReturnId()).orElse(null);
        var report = report(rental, checkIn, returned);
        String status = "NOT_CONFIRMED";
        Instant remediationAt = null;
        String note = "Chưa có xác nhận đã dọn xong hoặc bàn giao kho trong hồ sơ.";
        String blocked = null;
        if (rental.getActualReturnedAt() != null || rental.getCompletedAt() != null) {
            status = "RETURNED";
            remediationAt = rental.getActualReturnedAt() != null ? rental.getActualReturnedAt() : rental.getCompletedAt();
            note = blocked = "Kho đã được bàn giao hoặc hồ sơ thuê đã hoàn tất.";
        } else if (returned != null && (returned.getInspectedAt() != null || returned.getSetupReturnedAt() != null
            || Boolean.TRUE.equals(returned.getWarehouseEmptyVerified()))) {
            status = "VERIFIED_CLEARED";
            remediationAt = returned.getWarehouseEmptyVerifiedAt() != null ? returned.getWarehouseEmptyVerifiedAt()
                : returned.getInspectedAt() != null ? returned.getInspectedAt() : returned.getSetupReturnedAt();
            note = blocked = "Nhân viên đã xác nhận kho trống hoặc đã nghiệm thu. Cần xử lý tiếp hồ sơ trả kho.";
        } else if (returned != null && (returned.getReadyForInspectionAt() != null
            || ReturnAttendancePolicy.protectsFromRecovery(returned))) {
            status = "AWAITING_INSPECTION";
            remediationAt = returned.getReadyForInspectionAt();
            note = blocked = "Hồ sơ đang chờ nghiệm thu hoặc đang tiếp nhận khách. Cần kiểm tra kết quả trước khi mở thu hồi.";
        } else if (returned != null && returned.getCustomerClearedAt() != null
            && !Boolean.FALSE.equals(returned.getWarehouseEmptyVerified())) {
            status = "CUSTOMER_REPORTED";
            remediationAt = returned.getCustomerClearedAt();
            note = blocked = "Khách đã báo dọn xong, đang chờ nhân viên kiểm tra thực tế.";
        } else if (returned != null && Boolean.FALSE.equals(returned.getWarehouseEmptyVerified())) {
            status = "NOT_CLEARED";
            remediationAt = returned.getWarehouseEmptyVerifiedAt();
            note = "Nhân viên kiểm tra và ghi nhận kho chưa được dọn trống.";
        }
        Instant removalDeadline = rental.getInventoryRemovalDeadlineAt();
        if (blocked == null && (recovery.getReason() == ViolationRecovery.Reason.REMOVAL_OVERDUE
            || recovery.getReason() == ViolationRecovery.Reason.HANDOVER_FOLLOW_UP)
            && (removalDeadline == null || now.isBefore(removalDeadline))) {
            blocked = removalDeadline == null ? "Hồ sơ chưa có hạn dọn hàng để đối chiếu."
                : "Hạn dọn hàng đã được thay đổi và chưa đến hạn. Chưa đủ điều kiện mở thu hồi.";
        }
        return new Review(report, removalDeadline, status, remediationAt, note, blocked == null, blocked);
    }

    private Report report(Rental rental, CheckIn checkIn, ReturnCase returned) {
        JsonNode checklist = json(checkIn == null ? null : checkIn.getChecklistJson());
        JsonNode actual = json(checkIn == null ? null : checkIn.getInventoryActualJson());
        var goods = new ArrayList<Goods>();
        if (actual != null && actual.path("items").isArray()) {
            for (var item : actual.path("items")) {
                if (goods.size() == 50) break;
                if (item.path("name").isTextual()) goods.add(new Goods(item.path("name").asText(), item.path("quantity").asInt()));
            }
        }
        var evidence = new ArrayList<Evidence>();
        if (checkIn != null) addEvidence(evidence, "CHECK_IN", checkIn.getId(),
            references(checkIn.getChecklistJson()));
        if (returned != null) addEvidence(evidence, "RETURN_CASE", returned.getId(), references(returned.getEvidencePhotosJson()));
        var published = rental.getInventoryResultPublishedAt();
        if (published == null && checkIn != null) published = checkIn.getInventoryVerifiedAt();
        boolean available = checklist != null || actual != null;
        var performer = !available || checkIn == null ? null : checkIn.getPerformedBy();
        return new Report(available, performer == null ? null : performer.getFullName(), published,
            checkIn == null ? null : checkIn.getInventoryMismatchSummary(), checkIn == null ? null : checkIn.getInventoryDecisionNote(),
            text(checklist, "notes"), text(checklist, "goodsCondition"), text(checklist, "initialUnitCondition"),
            checklist != null && checklist.path("packageCount").isIntegralNumber() ? checklist.path("packageCount").asInt() : null,
            goods, evidence);
    }

    private void addEvidence(List<Evidence> output, String type, UUID id, Set<UUID> references) {
        if (id == null || references.isEmpty()) return;
        for (var file : files.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc(type, id)) {
            if (file.getStatus() == FileAssetStatus.ACTIVE && references.contains(file.getId()))
                output.add(new Evidence(file.getId(), file.getOriginalName(), file.getContentType()));
        }
    }

    private Set<UUID> references(String value) {
        var result = new HashSet<UUID>();
        if (value == null || value.isBlank()) return result;
        try {
            var node = mapper.readTree(value);
            var array = node.isArray() ? node : node.path("evidenceReferences");
            if (array.isArray()) for (var reference : array) {
                try { result.add(UUID.fromString(reference.asText())); }
                catch (IllegalArgumentException ignored) { }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        return result;
    }

    private JsonNode json(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var node = mapper.readTree(value);
            return node.isObject() && !node.isEmpty() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { return null; }
    }

    private String text(JsonNode node, String key) {
        return node != null && node.path(key).isTextual() ? node.path(key).asText() : null;
    }
}
