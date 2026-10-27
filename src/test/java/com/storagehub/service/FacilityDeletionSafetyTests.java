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
    @Mock GoodsTypeCatalogRepository goodsTypes;
    @Mock GoodsMaterialCatalogRepository goodsMaterials;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Spy com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    @InjectMocks FacilityLifecycleService service;

    private final UUID id = UUID.randomUUID();
    private final ActorPrincipal actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(),
        Set.of(RoleCode.BUSINESS), Set.of(SystemPermission.MANAGE_POLICIES.code()), Map.of());

    @Test void preservesFacilityWithReservationHistory() {
        Facility facility = new Facility();
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        service.deleteFacility(actor, id);
        assertThat(facility.getStatus()).isEqualTo(FacilityStatus.inactive);
        verify(facilities).saveAndFlush(facility);
        verify(facilities, never()).delete(any());
        verifyNoInteractions(reservations);
    }

    @Test void archivesFacilityAndTypesWithoutDeletingPhysicalUnits() {
        Facility facility = new Facility();
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        UnitType type = new UnitType();
        type.setStatus(UnitTypeStatus.active);
        when(unitTypes.findByFacility_IdOrderByCodeAsc(id)).thenReturn(List.of(type));
        service.deleteFacility(actor, id);
        verify(facilities).saveAndFlush(facility);
        verify(facilities, never()).delete(any());
        assertThat(facility.getStatus()).isEqualTo(FacilityStatus.inactive);
        assertThat(type.getStatus()).isEqualTo(UnitTypeStatus.archived);
        verify(units, never()).deleteAll(anyList());
    }

    @Test void archivesEmptyFacilityWithoutPhysicalDeletion() {
        Facility facility = new Facility();
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        service.deleteFacility(actor, id);
        verify(facilities).saveAndFlush(facility);
        verify(facilities, never()).delete(any());
        assertThat(facility.getStatus()).isEqualTo(FacilityStatus.inactive);
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

    @Test void managerArchivesTypeWithoutDeletingBookingHistory() {
        UnitType type = mockType();
        when(unitTypes.saveAndFlush(type)).thenReturn(type);
        service.deleteUnitType(manager(), id);
        verify(type).setStatus(UnitTypeStatus.archived);
        verify(unitTypes, never()).delete(any());
        verify(units, never()).deleteAll(anyList());
        verifyNoInteractions(reservations);
    }

    @Test void archivingTypeDoesNotChangeReservedPhysicalUnits() {
        UnitType type = mockType();
        when(unitTypes.saveAndFlush(type)).thenReturn(type);
        service.deleteUnitType(manager(), id);
        verify(type).setStatus(UnitTypeStatus.archived);
        verify(units, never()).save(any());
        verify(units, never()).deleteAll(anyList());
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

    @Test void decreasingQuantityArchivesAvailableUnitsWithoutDeletingHistory() {
        editableType();
        StorageUnit first = availableUnit("B-206");
        StorageUnit second = availableUnit("B-205");
        when(units.countByFacility_IdAndUnitType_IdAndStatusIn(
            eq(id), eq(id), eq(StorageUnitStatus.operationalStatuses()))).thenReturn(6L);
        when(units.findByFacility_Id(id)).thenReturn(List.of(second, first));

        service.updateUnitType(manager(), id, technicalRequest(new java.math.BigDecimal("4")));

        assertThat(first.getStatus()).isEqualTo(StorageUnitStatus.archived);
        assertThat(second.getStatus()).isEqualTo(StorageUnitStatus.archived);
        verify(units).saveAll(argThat(saved -> {
            var values = new ArrayList<StorageUnit>();
            saved.forEach(values::add);
            return values.size() == 2 && values.stream().allMatch(unit -> unit.getStatus() == StorageUnitStatus.archived);
        }));
        verify(units, never()).delete(any());
        verify(units, never()).deleteAll(anyList());
    }

    private StorageUnit availableUnit(String code) {
        StorageUnit unit = new StorageUnit();
        UnitType type = new UnitType();
        org.springframework.test.util.ReflectionTestUtils.setField(type, "id", id);
        unit.setUnitType(type);
        unit.setCode(code);
        unit.setStatus(StorageUnitStatus.available);
        unit.setAvailableFrom(java.time.Instant.now());
        return unit;
    }

    @Test void newUnitsNeverReuseCodesThatAlreadyExistInHistory() {
        UnitType type = new UnitType();
        type.setFacility(new Facility());
        when(units.existsByCode(anyString())).thenAnswer(invocation ->
            Set.of("B-201", "B-202", "B-203", "B-204").contains(invocation.getArgument(0)));

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "createPhysicalUnits", type, 2, "Tầng 2", "Khu B");

        var captor = ArgumentCaptor.forClass(StorageUnit.class);
        verify(units, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(StorageUnit::getCode)
            .containsExactly("B-205", "B-206");
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

    @Test void technicalApprovalRequiresExplicitPriceConfirmation() {
        UnitType type = editableType();
        service.updateUnitType(manager(), id, technicalRequest(new java.math.BigDecimal("5")));
        var captor = ArgumentCaptor.forClass(UnitTypeChangeRequest.class);
        verify(changes).saveAndFlush(captor.capture());
        UnitTypeChangeRequest request = captor.getValue();
        when(changes.findLockedById(request.getId())).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.reviewUnitTypeChange(actor, request.getId(), true, null))
            .hasMessageContaining("phải xác nhận giá thuê");
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
