package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.storagehub.api.rental.RentalSummaryResponse;
import com.storagehub.api.rental.StaffRentalDetailResponse;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.ActivityLogRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalSuspicionReportServiceTest {
    @Test
    void sendsOnlyToFacilityManagersAndTreatsAnIdenticalRequestAsAlreadySubmitted() {
        var queries = mock(RentalQueryService.class);
        var rentals = mock(RentalRepository.class);
        var users = mock(UserRepository.class);
        var activity = mock(ActivityLogRepository.class);
        var audit = mock(AuditLogService.class);
        var notifications = mock(NotificationService.class);
        var service = new RentalSuspicionReportService(queries, rentals, users, activity, audit, notifications);
        var reporterId = UUID.randomUUID();
        var managerId = UUID.randomUUID();
        var facilityId = UUID.randomUUID();
        var rentalId = UUID.randomUUID();
        var actor = new ActorPrincipal(reporterId, UUID.randomUUID(), Set.of(RoleCode.STAFF), Set.of(),
            Map.of(facilityId, FacilityScopeLevel.OPERATE));
        var summary = mock(RentalSummaryResponse.class);
        when(summary.facility()).thenReturn(new RentalSummaryResponse.Facility(facilityId, "Q1", "Quận 1"));
        when(summary.storageUnit()).thenReturn(new RentalSummaryResponse.Unit(UUID.randomUUID(), "M-01"));
        when(summary.customer()).thenReturn(new RentalSummaryResponse.Customer(UUID.randomUUID(), "Khách demo"));
        when(queries.staffDetail(actor, rentalId))
            .thenReturn(new StaffRentalDetailResponse(summary, UUID.randomUUID(), "RSV-01", null));
        var facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        var rental = mock(Rental.class);
        when(rental.getId()).thenReturn(rentalId);
        when(rental.getFacility()).thenReturn(facility);
        when(rental.getStatus()).thenReturn(RentalStatus.active);
        when(rentals.findByIdForUpdate(rentalId)).thenReturn(Optional.of(rental));
        var reporter = mock(User.class);
        when(reporter.getFullName()).thenReturn("Nhân viên demo");
        when(users.getReferenceById(reporterId)).thenReturn(reporter);
        var manager = mock(User.class);
        when(manager.getId()).thenReturn(managerId);
        when(users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facilityId, List.of(FacilityScopeLevel.MANAGE)))
            .thenReturn(List.of(manager));
        when(activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(anyString(), any(), anyString()))
            .thenReturn(List.of(), List.of(new ActivityLog()), List.of());
        when(activity.findReportsForRental("RentalSuspicionReport", "RENTAL_SUSPICION_REPORTED",
            facilityId.toString(), rentalId.toString())).thenReturn(List.of(), List.of(new ActivityLog()));
        var input = new RentalSuspicionReportService.Input(UUID.randomUUID(), Instant.now(),
            "Quan sát thấy thùng có nhãn cảnh báo.");

        var receipt = service.submit(actor, rentalId, input);

        assertThat(receipt.status()).isEqualTo("PENDING_MANAGER");
        assertThat(service.submit(actor, rentalId, input)).isEqualTo(receipt);
        var otherRequest = new RentalSuspicionReportService.Input(UUID.randomUUID(), Instant.now(), "Báo cáo khác.");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(actor, rentalId, otherRequest))
            .isInstanceOfSatisfying(com.storagehub.common.api.ApiException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verify(audit).recordMutation(eq(reporter), eq("RENTAL_SUSPICION_REPORTED"), eq("RentalSuspicionReport"),
            any(UUID.class), eq(facilityId), isNull(), any());
        verify(notifications).createNotification(eq(managerId), eq(NotificationType.SYSTEM), eq("Báo cáo nghi vấn hàng cấm"),
            contains("Quan sát thấy thùng có nhãn cảnh báo."), any(UUID.class), eq(RoleCode.STAFF));
        verify(notifications).createNotification(eq(reporterId), eq(NotificationType.SYSTEM),
            eq("Đã gửi báo cáo nghi vấn hàng cấm"), contains(receipt.code()), any(UUID.class), eq(RoleCode.STAFF));
        verifyNoMoreInteractions(notifications);
        verify(rental, never()).setStatus(any());
    }

    @Test
    void acceptanceAndClosureRecordEachDecisionOnlyOnce() {
        var rentals = mock(RentalRepository.class);
        var users = mock(UserRepository.class);
        var activity = mock(ActivityLogRepository.class);
        var audit = mock(AuditLogService.class);
        var queries = mock(RentalQueryService.class);
        var service = new RentalSuspicionReportService(queries, rentals, users, activity, audit, mock(NotificationService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper",
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

        var managerId = UUID.randomUUID();
        var facilityId = UUID.randomUUID();
        var reportId = UUID.randomUUID();
        var rentalId = UUID.randomUUID();
        var scope = Map.of(facilityId, FacilityScopeLevel.MANAGE);
        var permissions = Set.of(SystemPermission.VIEW_RENTALS.code());
        var manager = new ActorPrincipal(managerId, UUID.randomUUID(), Set.of(RoleCode.MANAGER), permissions, scope);
        var otherManager = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), permissions, scope);
        var facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        var author = mock(User.class);
        when(author.getId()).thenReturn(managerId);
        when(author.getFullName()).thenReturn("Quản lý Quận 1");
        when(users.getReferenceById(managerId)).thenReturn(author);
        var original = mock(ActivityLog.class);
        when(original.getEntityId()).thenReturn(reportId);
        when(original.getFacility()).thenReturn(facility);
        when(original.getAfterStateJson()).thenReturn("""
            {"code":"NV-01","rentalId":"%s","submittedAt":"2026-11-27T12:20:00Z",
             "observation":{"detectedAt":"2026-11-27T12:00:00Z","description":"Dấu hiệu ban đầu"}}
            """.formatted(rentalId));
        when(activity.findFirstByEntityTypeAndEntityIdAndActionAndFacility_IdInOrderByCreatedAtDesc(
            "RentalSuspicionReport", reportId, "RENTAL_SUSPICION_REPORTED", Set.of(facilityId)))
            .thenReturn(Optional.of(original));

        var acceptedAt = Instant.parse("2026-11-27T12:30:00Z");
        var acceptance = mock(ActivityLog.class);
        when(acceptance.getActor()).thenReturn(author);
        when(acceptance.getCreatedAt()).thenReturn(acceptedAt);
        when(activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(
            "RentalSuspicionReport", reportId, "RENTAL_SUSPICION_ACCEPTED")).thenReturn(List.of(), List.of(acceptance));
        when(audit.recordMutation(author, "RENTAL_SUSPICION_ACCEPTED", "RentalSuspicionReport",
            reportId, facilityId, "PENDING_MANAGER", "INVESTIGATING")).thenReturn(acceptance);

        var result = service.accept(manager, reportId);
        assertThat(result.report().status()).isEqualTo("INVESTIGATING");
        assertThat(result.report().acceptedByName()).isEqualTo("Quản lý Quận 1");
        assertThat(result.report().acceptedAt()).isEqualTo(acceptedAt);
        assertThat(result.report().description()).isEqualTo("Dấu hiệu ban đầu");
        assertThat(service.accept(manager, reportId)).isEqualTo(result);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.accept(otherManager, reportId))
            .isInstanceOfSatisfying(com.storagehub.common.api.ApiException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verify(audit).recordMutation(author, "RENTAL_SUSPICION_ACCEPTED", "RentalSuspicionReport",
            reportId, facilityId, "PENDING_MANAGER", "INVESTIGATING");

        var staff = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.STAFF), permissions, scope);
        var rental = mock(RentalSummaryResponse.class);
        when(rental.facility()).thenReturn(new RentalSummaryResponse.Facility(facilityId, "Q1", "Quận 1"));
        when(rental.storageUnit()).thenReturn(new RentalSummaryResponse.Unit(UUID.randomUUID(), "M-01"));
        when(rental.customer()).thenReturn(new RentalSummaryResponse.Customer(UUID.randomUUID(), "Khách demo"));
        when(queries.staffDetail(staff, rentalId)).thenReturn(new StaffRentalDetailResponse(rental, UUID.randomUUID(), "RSV-01", null));
        when(activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(
            "RentalSuspicionReport", reportId, "RENTAL_SUSPICION_REPORTED")).thenReturn(List.of(original));
        var staffView = service.staffReportDetail(staff, rentalId, reportId);
        assertThat(staffView.report().status()).isEqualTo("INVESTIGATING");
        assertThat(staffView.report().description()).isEqualTo("Dấu hiệu ban đầu");

        var decidedAt = Instant.parse("2026-11-27T13:00:00Z");
        var reason = "Đã đối chiếu và không phát hiện hàng cấm.";
        var closure = new ActivityLog();
        closure.setEntityId(reportId);
        closure.setAction("RENTAL_SUSPICION_CLOSED");
        closure.setAfterStateJson("{\"reason\":\"" + reason + "\"}");
        closure.setActor(author);
        org.springframework.test.util.ReflectionTestUtils.setField(closure, "createdAt", decidedAt);
        when(activity.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(
            "RentalSuspicionReport", reportId, "RENTAL_SUSPICION_CLOSED")).thenReturn(List.of(), List.of(closure));

        var input = new RentalSuspicionReportService.CloseInput("  " + reason + "  ");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closeWithoutViolation(staff, reportId, input))
            .isInstanceOfSatisfying(com.storagehub.common.api.ApiException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(403));
        var closed = service.closeWithoutViolation(manager, reportId, input);
        assertThat(closed.report().status()).isEqualTo("CLOSED_NO_VIOLATION");
        assertThat(closed.report().description()).isEqualTo("Dấu hiệu ban đầu");
        assertThat(closed.transfer()).isNull();
        assertThat(closed.closure()).isEqualTo(new RentalSuspicionReportService.Closure(reason, "Quản lý Quận 1", decidedAt));
        assertThat(service.closeWithoutViolation(manager, reportId,
            new RentalSuspicionReportService.CloseInput("Lý do khác"))).isEqualTo(closed);
        assertThat(service.staffReportDetail(staff, rentalId, reportId).closure()).isEqualTo(closed.closure());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.transferToRecovery(manager, reportId,
            new RentalSuspicionReportService.TransferInput("Kết luận mới", "Căn cứ mới")))
            .isInstanceOfSatisfying(com.storagehub.common.api.ApiException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verify(audit).recordMutation(author, "RENTAL_SUSPICION_CLOSED", "RentalSuspicionReport",
            reportId, facilityId, "INVESTIGATING", new RentalSuspicionReportService.CloseInput(reason));
        verify(rentals, never()).findByIdForUpdate(any());
    }
}
