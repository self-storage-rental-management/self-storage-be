package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecoveryReviewServiceTests {
    private final CheckInRepository checkIns = mock(CheckInRepository.class);
    private final ReturnCaseRepository returns = mock(ReturnCaseRepository.class);
    private final FileAssetRepository files = mock(FileAssetRepository.class);
    private final ReservationGoodsItemRepository declaredGoods = mock(ReservationGoodsItemRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RecoveryReviewService service = new RecoveryReviewService(checkIns, returns, files, mapper, declaredGoods);
    private final Rental rental = identified(new Rental());
    private final CheckIn checkIn = identified(new CheckIn());
    private final ReturnCase returned = identified(new ReturnCase());
    private final ViolationRecovery recovery = new ViolationRecovery();
    private final Instant now = Instant.parse("2026-11-26T10:00:00Z");

    @BeforeEach
    void setUp() {
        var reservation = identified(new Reservation());
        rental.setReservation(reservation);
        rental.setInventoryRemovalDeadlineAt(now.minusSeconds(600));
        rental.setInventoryResultPublishedAt(now.minusSeconds(7200));
        recovery.setRental(rental);
        recovery.setReason(ViolationRecovery.Reason.REMOVAL_OVERDUE);
        recovery.setSourceReturnId(returned.getId());
        when(checkIns.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(checkIn));
        when(returns.findById(returned.getId())).thenReturn(Optional.of(returned));
    }

    @Test
    void preparationSeparatesDeclaredGoodsFromActualInventoryLinesAndKeepsZeroQuantity() throws Exception {
        var item = identified(new ReservationGoodsItem());
        item.setGoodsCode("HH-019");
        item.setCustomGoodsName("Tủ gỗ");
        item.setMaterialName("Gỗ");
        item.setQuantity(2);
        item.setLengthCm(new java.math.BigDecimal("120.00"));
        item.setFragile(true);
        item.setCustomerNote("Tránh va đập");
        item.setCategory(GoodsCategory.FURNITURE);
        item.setStackable(true);
        item.setMaxStackLayers(3);
        item.setAllowHorizontalPlacement(true);
        rental.getReservation().setGoodsCondition("Bọc màng / túi bảo vệ");
        var declaredPhoto = identified(new FileAsset());
        declaredPhoto.setContentType("image/jpeg");
        declaredPhoto.setOriginalName("hang-khai-bao.jpg");
        when(files.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc("RESERVATION_GOODS_ITEM", item.getId()))
            .thenReturn(List.of(declaredPhoto));
        when(declaredGoods.findAllByReservation_IdOrderByCreatedAtAsc(rental.getReservation().getId())).thenReturn(List.of(item));
        checkIn.setInventoryActualJson("""
            {"packageCount":0,"identityDocumentNumber":"SECRET-ID","lines":[
              {"goodsItemId":"%s","actualQuantity":0,"condition":"NOT_PRESENT","note":"Không tìm thấy kiện",
               "actualGoods":{"materialName":"Kim loại","identityDocumentNumber":"SECRET-LINE"}}]}
            """.formatted(item.getId()));
        checkIn.setInventoryDraftJson("{\"notes\":\"SECRET-DRAFT\"}");
        var prepared = service.preparation(recovery);
        assertThat(prepared.declared()).hasSize(1);
        assertThat(prepared.declared().getFirst().name()).isEqualTo("Tủ gỗ");
        assertThat(prepared.declared().getFirst().quantity()).isEqualTo(2);
        assertThat(prepared.declared().getFirst().fragile()).isTrue();
        assertThat(prepared.declared().getFirst().category()).isEqualTo(GoodsCategory.FURNITURE);
        assertThat(prepared.declared().getFirst().stackable()).isTrue();
        assertThat(prepared.declared().getFirst().maxStackLayers()).isEqualTo(3);
        assertThat(prepared.declared().getFirst().allowHorizontalPlacement()).isTrue();
        assertThat(prepared.declared().getFirst().images()).extracting(RecoveryReviewService.Evidence::id).containsExactly(declaredPhoto.getId());
        assertThat(prepared.packaging()).isEqualTo("Bọc màng / túi bảo vệ");
        assertThat(prepared.inventory().packageCount()).isZero();
        assertThat(prepared.inventory().goods()).containsExactly(new RecoveryReviewService.Goods(
            "Tủ gỗ", 0, "Không có tại kho", "Kim loại", "Không tìm thấy kiện"));
        assertThat(mapper.writeValueAsString(prepared)).doesNotContain("SECRET-ID", "SECRET-LINE", "SECRET-DRAFT", "NOT_PRESENT");
    }

    @Test
    void preparationDoesNotTreatUnpublishedHandoverOrDraftAsAnInventoryResult() {
        rental.setInventoryResultPublishedAt(null);
        checkIn.setChecklistJson("{\"notes\":\"Đã nhận chìa khóa\"}");
        checkIn.setInventoryDraftJson("{\"packageCount\":3,\"notes\":\"Bản nháp kiểm kê\"}");
        var prepared = service.preparation(recovery);
        assertThat(prepared.declared()).isEmpty();
        assertThat(prepared.inventory().available()).isFalse();
        assertThat(prepared.inventory().goods()).isEmpty();
    }

    @Test
    void projectsSavedReportAndBoundEvidenceWithoutRawIdentityOrDraftData() throws Exception {
        checkIn.setChecklistJson("""
            {"notes":"Đã đo lại kích thước", "goodsCondition":"Nguyên vẹn", "initialUnitCondition":"Kho sạch",
             "packageCount":2,"identityDocumentNumber":"SECRET-ID","lockReference":"SECRET-LOCK"}
            """);
        checkIn.setInventoryMismatchSummary("Hàng vượt kích thước đã khai báo");
        checkIn.setInventoryDecisionNote("Khách cần dọn hàng ra khỏi kho");
        checkIn.setInventoryActualJson("{\"items\":[{\"name\":\"Tủ lạnh\",\"quantity\":2}]}");
        checkIn.setInventoryDraftJson("{\"notes\":\"Bản nháp chưa công bố\"}");
        var photo = identified(new FileAsset());
        photo.setOriginalName("kiem-ke.jpg");
        photo.setContentType("image/jpeg");
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(checkIn.getChecklistJson());
        report.putArray("evidenceReferences").add(photo.getId().toString());
        checkIn.setChecklistJson(mapper.writeValueAsString(report));
        var unrelated = identified(new FileAsset());
        unrelated.setOriginalName("identity-demo.jpg");
        var inactive = identified(new FileAsset());
        inactive.setStatus(FileAssetStatus.DELETED);
        when(files.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc("CHECK_IN", checkIn.getId()))
            .thenReturn(List.of(photo, inactive, unrelated));

        var result = service.read(recovery, now);

        assertThat(result.report().available()).isTrue();
        assertThat(result.report().notes()).isEqualTo("Đã đo lại kích thước");
        assertThat(result.report().packageCount()).isEqualTo(2);
        assertThat(result.report().goods()).containsExactly(new RecoveryReviewService.Goods("Tủ lạnh", 2));
        assertThat(result.report().evidence()).extracting(RecoveryReviewService.Evidence::id).containsExactly(photo.getId());
        assertThat(mapper.writeValueAsString(result)).doesNotContain("SECRET-ID", "SECRET-LOCK", "Bản nháp chưa công bố", "identity-demo.jpg");
        assertThat(result.remediationStatus()).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void missingReportAndRemediationStayUnknownInsteadOfBeingInvented() {
        var result = service.read(recovery, now);
        assertThat(result.report().available()).isFalse();
        assertThat(result.report().recordedBy()).isNull();
        assertThat(result.remediationStatus()).isEqualTo("NOT_CONFIRMED");
        assertThat(result.remediationNote()).contains("Chưa có xác nhận");
    }

    @Test
    void customerClearanceNeedsStaffVerificationBeforeOpeningRecovery() {
        returned.setCustomerClearedAt(now.minusSeconds(100));
        var result = service.read(recovery, now);
        assertThat(result.openingAllowed()).isFalse();
        assertThat(result.remediationStatus()).isEqualTo("CUSTOMER_REPORTED");
        returned.setWarehouseEmptyVerified(false);
        returned.setWarehouseEmptyVerifiedAt(now);
        assertThat(service.read(recovery, now).openingAllowed()).isTrue();
        assertThat(service.read(recovery, now).remediationStatus()).isEqualTo("NOT_CLEARED");
    }

    @Test
    void staffVerifiedClearanceBlocksRecoveryEvenWithoutAnExplicitSourceReturnLink() {
        recovery.setSourceReturnId(null);
        returned.setWarehouseEmptyVerified(true);
        returned.setWarehouseEmptyVerifiedAt(now.minusSeconds(60));
        when(returns.findByRental_Id(rental.getId())).thenReturn(List.of(returned));
        var result = service.read(recovery, now);
        assertThat(result.openingAllowed()).isFalse();
        assertThat(result.remediationStatus()).isEqualTo("VERIFIED_CLEARED");
        assertThat(result.remediationAt()).isEqualTo(returned.getWarehouseEmptyVerifiedAt());
    }

    @Test
    void extendedRemovalDeadlineIsReadLiveAndPreventsPrematureRecovery() {
        rental.setInventoryRemovalDeadlineAt(now.plusSeconds(3600));
        var result = service.read(recovery, now);
        assertThat(result.removalDeadlineAt()).isEqualTo(rental.getInventoryRemovalDeadlineAt());
        assertThat(result.openingAllowed()).isFalse();
        assertThat(result.blockingReason()).contains("chưa đến hạn");
    }

    @Test
    void customerPreparationIncludesDeclaredQuantitiesAndOnlyPublishedInventory() throws Exception {
        var item = identified(new ReservationGoodsItem());
        item.setCustomGoodsName("Thùng tài liệu");
        item.setQuantity(100);
        when(declaredGoods.findAllByReservation_IdOrderByCreatedAtAsc(rental.getReservation().getId())).thenReturn(List.of(item));
        checkIn.setInventoryActualJson("{\"items\":[{\"name\":\"Thùng tài liệu\",\"quantity\":99}]}");
        checkIn.setChecklistJson("{\"notes\":\"INTERNAL-NOTE\",\"identityDocumentNumber\":\"PRIVATE-ID\"}");
        checkIn.setInventoryDraftJson("{\"notes\":\"PRIVATE-DRAFT\"}");
        var published = service.customerPreparation(recovery);
        assertThat(published.declared().getFirst().quantity()).isEqualTo(100);
        assertThat(published.inventory().goods()).containsExactly(new RecoveryReviewService.Goods("Thùng tài liệu", 99));
        assertThat(published.inventory().notes()).isNull();
        assertThat(published.inventory().evidence()).isEmpty();
        assertThat(mapper.writeValueAsString(published)).doesNotContain("INTERNAL-NOTE", "PRIVATE-ID", "PRIVATE-DRAFT");
        rental.setInventoryResultPublishedAt(null);
        var unpublished = service.customerPreparation(recovery);
        assertThat(unpublished.declared()).hasSize(1);
        assertThat(unpublished.inventory().available()).isFalse();
        assertThat(unpublished.inventory().goods()).isEmpty();
    }

    private static <T extends BaseEntity> T identified(T value) {
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
        return value;
    }
}
