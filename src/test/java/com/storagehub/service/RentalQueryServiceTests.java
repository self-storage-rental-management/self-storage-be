package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.storagehub.api.rental.RentalQuery;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;

class RentalQueryServiceTests {
    RentalRepository repo = mock(RentalRepository.class);
    RentalQueryService service = new RentalQueryService(repo);
    UUID facility = UUID.randomUUID(); UUID customer = UUID.randomUUID();
    ActorPrincipal actor(RoleCode role, boolean permission, Map<UUID,FacilityScopeLevel> scopes) {
        return new ActorPrincipal(customer,UUID.randomUUID(),Set.of(role),
            permission ? Set.of(SystemPermission.VIEW_RENTALS.code()) : Set.of(),scopes);
    }
    @Test void rejectsMissingManagerPermissionAndScopeBeforeQuery() {
        var q = RentalQuery.parse(new LinkedMultiValueMap<>(),true);
        assertThatThrownBy(() -> service.list(actor(RoleCode.MANAGER,false,Map.of(facility,FacilityScopeLevel.READ)),q,true,"test"))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(actor(RoleCode.MANAGER,true,Map.of()),q,true,"test"))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(actor(RoleCode.ADMIN,true,Map.of(facility,FacilityScopeLevel.MANAGE)),q,true,"test"))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(repo);
    }
    @Test void missingOrInvisibleDetailIs404() {
        when(repo.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(actor(RoleCode.CUSTOMER,false,Map.of()),UUID.randomUUID(),false))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }
    @Test void usesAppliedRateAndUnknownFinancialAndDoesNotWrite() {
        var r = rental(); when(repo.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.of(r));
        var dto = service.detail(actor(RoleCode.CUSTOMER,false,Map.of()),r.getId(),false);
        assertThat(dto.monthlyPrice()).isEqualByComparingTo("9500000");
        assertThat(dto.unitType().name()).isEqualTo("Medium");
        assertThat(dto.financialSummary().completeness()).isEqualTo("UNKNOWN");
        assertThat(dto.financialSummary().outstandingAmount()).isNull();
        assertThat(dto.access().status()).isNull();
        verify(repo,never()).save(any());
    }
    @Test void brokenRelationshipFailsClosedWithoutForeignIds() {
        var r = rental(); r.getStorageUnit().setFacility(entity(new Facility(),UUID.randomUUID()));
        when(repo.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.detail(actor(RoleCode.CUSTOMER,false,Map.of()),r.getId(),false))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus().value()).isEqualTo(409); assertThat(ex.getDetails()).isNull(); });
        verify(repo,never()).save(any());
    }
    Rental rental() {
        Facility f=entity(new Facility(),facility); f.setCode("F1"); f.setName("Facility");
        User u=entity(new User(),customer); u.setFullName("Customer");
        UnitType t=entity(new UnitType(),UUID.randomUUID()); t.setFacility(f); t.setCode("M"); t.setName("Medium"); t.setMonthlyPrice(new BigDecimal("15000000"));
        StorageUnit unit=entity(new StorageUnit(),UUID.randomUUID()); unit.setFacility(f); unit.setUnitType(t); unit.setCode("M-1");
        Reservation reservation=entity(new Reservation(),UUID.randomUUID()); reservation.setFacility(f); reservation.setCustomer(u);
        Rental r=entity(new Rental(),UUID.randomUUID()); r.setCustomer(u); r.setFacility(f); r.setStorageUnit(unit); r.setReservation(reservation);
        r.setStartDate(LocalDate.of(2026,9,1)); r.setContractEndDate(LocalDate.of(2026,10,1)); r.setMonthlyPrice(new BigDecimal("9500000")); return r;
    }
    <T extends BaseEntity> T entity(T value,UUID id) { ReflectionTestUtils.setField(value,"id",id); return value; }
}
