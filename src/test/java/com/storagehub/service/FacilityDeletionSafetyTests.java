package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FacilityDeletionSafetyTests {
    @Mock FacilityRepository facilities;
    @Mock UnitTypeRepository unitTypes;
    @Mock StorageUnitRepository units;
    @Mock RentalPackagePolicyRepository packages;
    @Mock FacilityScopeService scopes;
    @Mock AdminAuthorizationService authorization;
    @Mock AuditLogService audit;
    @Mock UserFacilityScopeRepository userFacilityScopes;
    @Mock ReservationRepository reservations;
    @Mock NotificationService notifications;
    @Mock UnitTypeChangeRequestRepository changes;
    @Mock UserRepository users;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Spy com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    @InjectMocks FacilityLifecycleService service;

    private final UUID id = UUID.randomUUID();
    private final ActorPrincipal actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(),
        Set.of(RoleCode.BUSINESS), Set.of(SystemPermission.MANAGE_POLICIES.code()), Map.of());

    @Test void preservesFacilityWithReservationHistory() {
        when(facilities.findById(id)).thenReturn(Optional.of(new Facility()));
        when(reservations.existsByFacility_Id(id)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteFacility(actor, id)).hasMessageContaining("hồ sơ");
        verify(facilities, never()).delete(any());
    }

    @Test void allowsDeletingFacilityWithPhysicalUnitsWhenNoReservations() {
        Facility facility = new Facility();
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(reservations.existsByFacility_Id(id)).thenReturn(false);
        service.deleteFacility(actor, id);
        verify(facilities).delete(facility);
        verify(facilities).flush();
    }

    @Test void allowsDeletingEmptyFacility() {
        Facility facility = new Facility();
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        service.deleteFacility(actor, id);
        verify(facilities).delete(facility);
        verify(facilities).flush();
    }

    @Test void rejectsCustomerDeletion() {
        ActorPrincipal customer = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());
        assertThatThrownBy(() -> service.deleteFacility(customer, id)).hasMessageContaining("Only Business");
        verifyNoInteractions(facilities);
    }

    private ActorPrincipal manager() {
        return new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.MANAGE_INVENTORY.code()), Map.of(id, FacilityScopeLevel.MANAGE));
    }

    private UnitType mockType() {
        UnitType type = mock(UnitType.class);
        Facility facility = mock(Facility.class);
        when(facility.getId()).thenReturn(id);
        when(type.getFacility()).thenReturn(facility);
        when(unitTypes.findByIdForUpdate(id)).thenReturn(Optional.of(type));
        return type;
    }

    @Test void managerCannotDeleteTypeWithBookingHistory() {
        mockType();
        when(reservations.existsByUnitType_Id(id)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteUnitType(manager(), id)).hasMessageContaining("hồ sơ");
        verify(unitTypes, never()).delete(any());
        verify(units, never()).deleteAll(anyList());
    }

    @Test void managerCannotDeleteNonEmptyType() {
        UnitType type = mockType();
        when(type.getId()).thenReturn(id);
        StorageUnit unit = mock(StorageUnit.class);
        when(unit.getId()).thenReturn(id);
        when(unit.getUnitType()).thenReturn(type);
        when(unit.getStatus()).thenReturn(StorageUnitStatus.reserved);
        when(units.findByFacility_Id(id)).thenReturn(List.of(unit));
        when(units.findByIdForUpdate(id)).thenReturn(Optional.of(unit));
        assertThatThrownBy(() -> service.deleteUnitType(manager(), id)).hasMessageContaining("trống");
        verify(unitTypes, never()).delete(any());
    }

    @Test void managerCannotDeleteAnotherFacilityType() {
        mockType();
        ActorPrincipal actor = manager();
        doThrow(new IllegalStateException("outside facility scope")).when(scopes).assertCanManage(actor, id);
        assertThatThrownBy(() -> service.deleteUnitType(actor, id)).hasMessageContaining("scope");
        verify(unitTypes, never()).delete(any());
    }

    @Test void imageEditKeepsPublishedStatusAndNotifiesBusiness() {
        UnitType type = editableType();
        var request = technicalRequest(new java.math.BigDecimal("4.00"));
        service.updateUnitType(manager(), id, request);
        assertThat(type.getStatus()).isEqualTo(UnitTypeStatus.active);
        assertThat(type.getImageUrl()).isNull();
        assertThat(type.getDetailImageUrl()).isNull();
        verify(notifications).notifyBusinessOfUnitTypeReview(eq(type), any());
        verify(unitTypes, never()).save(any());
        verify(reservations, never()).existsByUnitType_Id(any());
    }

    @Test void technicalEditRequiresReviewAndNotifiesBusiness() {
        UnitType type = editableType();
        service.updateUnitType(manager(), id, technicalRequest(new java.math.BigDecimal("5")));
        assertThat(type.getStatus()).isEqualTo(UnitTypeStatus.active);
        assertThat(type.getLengthM()).isEqualByComparingTo("4");
        verify(notifications).notifyBusinessOfUnitTypeReview(eq(type), any());
        verify(unitTypes, never()).save(any());
    }

    private UnitTypeChangeRequest submittedChange(UnitType type) {
        service.updateUnitType(manager(), id, technicalRequest(new java.math.BigDecimal("4")));
        var captor = ArgumentCaptor.forClass(UnitTypeChangeRequest.class);
        verify(changes).saveAndFlush(captor.capture());
        UnitTypeChangeRequest request = captor.getValue();
        when(changes.findLockedById(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    @Test void approvalAppliesMetadataWithoutUnpublishing() {
        UnitType type = editableType();
        UnitTypeChangeRequest request = submittedChange(type);
        service.reviewUnitTypeChange(actor, request.getId(), true, null);
        assertThat(type.getName()).isEqualTo("Kho M");
        assertThat(type.getDescription()).isEqualTo("Mô tả");
        assertThat(type.getImageUrl()).isEqualTo("/images/customer-units/kho-m.jpg");
        assertThat(type.getDetailImageUrl()).isEqualTo("/images/customer-units/layout-m.png");
        assertThat(type.getStatus()).isEqualTo(UnitTypeStatus.active);
        assertThat(request.getStatus()).isEqualTo("APPROVED");
        verify(notifications).notifyUnitTypeReviewResult(request);
        verify(unitTypes).save(type);
    }

    @Test void rejectionKeepsOriginalAndRecordsReason() {
        UnitType type = editableType();
        UnitTypeChangeRequest request = submittedChange(type);
        service.reviewUnitTypeChange(actor, request.getId(), false, "Ảnh không phù hợp");
        assertThat(type.getImageUrl()).isNull();
        assertThat(type.getStatus()).isEqualTo(UnitTypeStatus.active);
        assertThat(request.getStatus()).isEqualTo("REJECTED");
        assertThat(request.getRejectionReason()).isEqualTo("Ảnh không phù hợp");
        verify(unitTypes, never()).save(any());
    }

    @Test void rejectionRequiresReason() {
        UnitTypeChangeRequest request = submittedChange(editableType());
        assertThatThrownBy(() -> service.reviewUnitTypeChange(actor, request.getId(), false, " "))
            .hasMessageContaining("lý do");
        assertThat(request.getStatus()).isEqualTo("PENDING");
    }

    @Test void reviewCannotBeRepeated() {
        UnitTypeChangeRequest request = submittedChange(editableType());
        request.setStatus("APPROVED");
        assertThatThrownBy(() -> service.reviewUnitTypeChange(actor, request.getId(), true, null))
            .hasMessageContaining("đã được xử lý");
        verify(unitTypes, never()).save(any());
    }

    @Test void blocksSecondPendingRequest() {
        editableType();
        when(changes.existsByUnitTypeIdAndStatus(id, "PENDING")).thenReturn(true);
        assertThatThrownBy(() -> service.updateUnitType(manager(), id, technicalRequest(new java.math.BigDecimal("4"))))
            .hasMessageContaining("chờ BO duyệt");
        verify(changes, never()).saveAndFlush(any());
    }

    @Test void approvalRejectsStaleOriginalData() {
        UnitType type = editableType();
        UnitTypeChangeRequest request = submittedChange(type);
        type.setDescription("Thông tin đã được thay đổi bởi luồng khác");
        assertThatThrownBy(() -> service.reviewUnitTypeChange(actor, request.getId(), true, null))
            .hasMessageContaining("Thông tin gốc");
        assertThat(request.getStatus()).isEqualTo("PENDING");
        verify(unitTypes, never()).save(any());
    }

    @Test void technicalApprovalRechecksNewBookingHistory() {
        UnitType type = editableType();
        service.updateUnitType(manager(), id, technicalRequest(new java.math.BigDecimal("5")));
        var captor = ArgumentCaptor.forClass(UnitTypeChangeRequest.class);
        verify(changes).saveAndFlush(captor.capture());
        UnitTypeChangeRequest request = captor.getValue();
        when(changes.findLockedById(request.getId())).thenReturn(Optional.of(request));
        when(reservations.existsByUnitType_Id(id)).thenReturn(true);
        assertThatThrownBy(() -> service.reviewUnitTypeChange(actor, request.getId(), true, null))
            .hasMessageContaining("hồ sơ đặt/thuê");
        assertThat(type.getLengthM()).isEqualByComparingTo("4");
        assertThat(request.getStatus()).isEqualTo("PENDING");
    }

    @Test void customerCannotReview() {
        var customer = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());
        assertThatThrownBy(() -> service.reviewUnitTypeChange(customer, id, true, null)).hasMessageContaining("Only Business");
        verifyNoInteractions(changes);
    }

    private UnitType editableType() {
        UnitType type = new UnitType();
        org.springframework.test.util.ReflectionTestUtils.setField(type, "id", id);
        Facility facility = mock(Facility.class);
        when(facility.getId()).thenReturn(id);
        type.setFacility(facility); type.setCode("M"); type.setStatus(UnitTypeStatus.active);
        var four = new java.math.BigDecimal("4");
        type.setLengthM(four); type.setWidthM(four); type.setHeightM(four); type.setMaxLoadKg(four);
        type.setRackCount(4); type.setRackLengthM(four); type.setRackWidthM(four); type.setRackHeightM(four);
        type.setAisleWidthM(four); type.setTrolleyType("Xe đẩy"); type.setSmallBoxCapacity(10); type.setLargeBoxCapacity(5);
        when(unitTypes.findByIdForUpdate(id)).thenReturn(Optional.of(type));
        lenient().when(changes.saveAndFlush(any())).thenAnswer(invocation -> {
            UnitTypeChangeRequest request = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
            return request;
        });
        return type;
    }

    private com.storagehub.api.facility.ManagerUnitTypeRequest technicalRequest(java.math.BigDecimal length) {
        var four = new java.math.BigDecimal("4.00");
        return new com.storagehub.api.facility.ManagerUnitTypeRequest("M", "Kho M", "Mô tả", length,
            four, four, four, 4, four, four, four, four, "Xe đẩy", 10, 5,
            "/images/customer-units/kho-m.jpg", 4, "2", "Khu B", "/images/customer-units/layout-m.png");
    }
}
