package com.storagehub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.time.Instant;
import java.math.BigDecimal;
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
    private final ReservationGoodsItemRepository declaredGoods;

    public record Evidence(UUID id, String name, String contentType) {}
    public record Goods(String name, int quantity, String condition, String material, String note) {
        public Goods(String name, int quantity) { this(name, quantity, null, null, null); }
    }
    public record DeclaredGood(String code, String name, String material, int quantity, BigDecimal lengthCm,
        BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg, boolean fragile, String description, String note,
        String categoryName, GoodsCategory category, boolean stackable, int maxStackLayers, boolean allowHorizontalPlacement,
        List<Evidence> images) {}
    public record Preparation(List<DeclaredGood> declared, Report inventory, String packaging) {}
    public record Report(boolean available, String recordedBy, Instant publishedAt, String mismatchSummary,
        String decisionNote, String notes, String goodsCondition, String unitCondition,
        Integer packageCount, List<Goods> goods, List<Evidence> evidence) {}
    public record Review(Report report, Instant removalDeadlineAt, String remediationStatus,
        Instant remediationAt, String remediationNote, boolean openingAllowed, String blockingReason) {}

    public Preparation preparation(ViolationRecovery recovery) {
        var rental = recovery.getRental();
        var declared = declaredGoods.findAllByReservation_IdOrderByCreatedAtAsc(rental.getReservation().getId());
        var checkIn = checkIns.findByReservation_Id(rental.getReservation().getId()).orElse(null);
        return new Preparation(declared.stream().map(item -> new DeclaredGood(item.getGoodsCode(), goodsName(item),
            material(item), item.getQuantity(), item.getLengthCm(), item.getWidthCm(), item.getHeightCm(), item.getWeightKg(),
            item.isFragile(), item.getDescription(), item.getCustomerNote(), item.getCategoryName(), item.getCategory(),
            item.isStackable(), item.getMaxStackLayers(), item.isAllowHorizontalPlacement(),
            files.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc("RESERVATION_GOODS_ITEM", item.getId()).stream()
                .filter(file -> file.getStatus() == FileAssetStatus.ACTIVE && file.getContentType() != null
                    && file.getContentType().startsWith("image/"))
                .map(file -> new Evidence(file.getId(), file.getOriginalName(), file.getContentType())).toList())).toList(),
            report(rental, checkIn, null, declared), rental.getReservation().getGoodsCondition());
    }

    public Preparation customerPreparation(ViolationRecovery recovery) {
        var preparation = preparation(recovery);
        var inventory = preparation.inventory();
        // Customers see their own declared goods and published results, without operational notes or evidence.
        boolean published = inventory.available() && inventory.publishedAt() != null;
        return new Preparation(preparation.declared(), published
            ? new Report(true, inventory.recordedBy(), inventory.publishedAt(), inventory.mismatchSummary(),
                inventory.decisionNote(), null, inventory.goodsCondition(), null, inventory.packageCount(),
                inventory.goods().stream().map(item -> new Goods(item.name(), item.quantity(), item.condition(), item.material(), null)).toList(),
                List.of())
            : new Report(false, null, null, null, null, null, null, null, null, List.of(), List.of()), preparation.packaging());
    }

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
        return report(rental, checkIn, returned, null);
    }

    private Report report(Rental rental, CheckIn checkIn, ReturnCase returned, List<ReservationGoodsItem> declared) {
        JsonNode checklist = json(checkIn == null ? null : checkIn.getChecklistJson());
        JsonNode actual = json(checkIn == null ? null : checkIn.getInventoryActualJson());
        var goods = new ArrayList<Goods>();
        if (actual != null && actual.path("items").isArray()) {
            for (var item : actual.path("items")) {
                if (goods.size() == 50) break;
                if (item.path("name").isTextual()) goods.add(new Goods(item.path("name").asText(), item.path("quantity").asInt()));
            }
        }
        if (actual != null && actual.path("lines").isArray()) {
            if (declared == null) declared = declaredGoods.findAllByReservation_IdOrderByCreatedAtAsc(rental.getReservation().getId());
            var byId = new HashMap<String, ReservationGoodsItem>();
            for (var item : declared) byId.put(item.getId().toString(), item);
            for (var line : actual.path("lines")) {
                var original = byId.get(line.path("goodsItemId").asText());
                var measured = line.path("actualGoods");
                String name = first(text(measured, "customGoodsName"), text(measured, "categoryName"),
                    original == null ? null : goodsName(original), "Hàng hóa đã kiểm kê");
                String condition = switch (line.path("condition").asText()) {
                    case "INTACT" -> "Nguyên vẹn";
                    case "DAMAGED" -> "Hư hỏng";
                    case "NOT_PRESENT" -> "Không có tại kho";
                    default -> null;
                };
                goods.add(new Goods(name, line.path("actualQuantity").asInt(), condition,
                    first(text(measured, "customMaterial"), text(measured, "materialName"),
                        original == null ? null : material(original)), text(line, "note")));
            }
        }
        var evidence = new ArrayList<Evidence>();
        if (checkIn != null) addEvidence(evidence, "CHECK_IN", checkIn.getId(),
            references(checkIn.getChecklistJson()));
        if (returned != null) addEvidence(evidence, "RETURN_CASE", returned.getId(), references(returned.getEvidencePhotosJson()));
        var published = rental.getInventoryResultPublishedAt();
        if (published == null && checkIn != null) published = checkIn.getInventoryVerifiedAt();
        boolean available = actual != null || checklist != null && published != null;
        var performer = !available || checkIn == null ? null : checkIn.getPerformedBy();
        Integer packageCount = null;
        if (actual != null && actual.path("packageCount").isIntegralNumber()) packageCount = actual.path("packageCount").asInt();
        else if (checklist != null && checklist.path("packageCount").isIntegralNumber()) packageCount = checklist.path("packageCount").asInt();
        return new Report(available, performer == null ? null : performer.getFullName(), published,
            checkIn == null ? null : checkIn.getInventoryMismatchSummary(), checkIn == null ? null : checkIn.getInventoryDecisionNote(),
            text(checklist, "notes"), text(checklist, "goodsCondition"), text(checklist, "initialUnitCondition"),
            packageCount, goods, evidence);
    }

    private String goodsName(ReservationGoodsItem item) {
        return first(item.getCustomGoodsName(), item.getCategoryName(), "Hàng hóa đã khai báo");
    }

    private String material(ReservationGoodsItem item) { return first(item.getCustomMaterial(), item.getMaterialName()); }

    private String first(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
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
