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
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RecoveryReviewService service = new RecoveryReviewService(checkIns, returns, files, mapper);
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

    private static <T extends BaseEntity> T identified(T value) {
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
        return value;
    }
}
