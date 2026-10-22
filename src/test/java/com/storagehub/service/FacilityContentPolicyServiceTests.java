package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.configuration.FacilityContentPolicy;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FacilityContentPolicyServiceTests {
    @Mock FacilityPolicyCatalogRepository catalogs;
    @Mock FacilityRepository facilities;
    @Mock AdminAuthorizationService authorization;
    @Mock AuditLogService audit;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks FacilityContentPolicyService service;
    ActorPrincipal bo = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.BUSINESS), Set.of(), Map.of());
    private FacilityContentPolicy policy(String scope, List<UUID> ids) {
        return new FacilityContentPolicy("p1", "Chính sách", "Giá trị", "Cơ sở", true, "Mô tả", "", scope, ids, "billing");
    }
    @Test void deletedPolicyDoesNotReappearFromDefaults() {
        var existing = new FacilityPolicyCatalog(); existing.setId("facility-content-policies"); existing.setVersion(3); existing.setContentJson("[]");
        when(catalogs.findById(anyString())).thenReturn(Optional.of(existing));
        assertThat(service.list(bo).policies()).isEmpty();
    }
    @Test void preventsOverwritingAnotherSessionsChanges() {
        var existing = new FacilityPolicyCatalog(); existing.setVersion(4); existing.setContentJson("[]");
        when(catalogs.findById(anyString())).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.save(bo, 3, List.of())).hasMessageContaining("nơi khác");
        verify(catalogs, never()).saveAndFlush(any());
    }
    @Test void customerSeesOnlyMatchingFacilityAndGlobalPolicies() throws Exception {
        UUID own = UUID.randomUUID(), other = UUID.randomUUID();
        var existing = new FacilityPolicyCatalog();
        existing.setContentJson(mapper.writeValueAsString(List.of(policy("specific", List.of(other)))));
        when(catalogs.findById(anyString())).thenReturn(Optional.of(existing));
        var facility = new Facility(); facility.setStatus(FacilityStatus.active);
        when(facilities.findById(own)).thenReturn(Optional.of(facility));
        assertThat(service.customerPolicies(own)).isEmpty();
        existing.setContentJson(mapper.writeValueAsString(List.of(policy("specific", List.of(own)))));
        assertThat(service.customerPolicies(own)).hasSize(1);
        existing.setContentJson(mapper.writeValueAsString(List.of(policy("all", List.of()))));
        assertThat(service.customerPolicies(own)).hasSize(1);
    }
    @Test void managerCannotWriteBusinessPolicies() {
        var manager = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), Set.of(), Map.of());
        assertThatThrownBy(() -> service.save(manager, -1, List.of())).hasMessageContaining("Chỉ BO");
        verifyNoInteractions(catalogs);
    }
    @Test void rejectsSpecificPolicyWithoutFacility() {
        when(catalogs.findById(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(bo, -1, List.of(policy("specific", List.of())))).hasMessageContaining("chọn cơ sở");
    }
}
