package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorPrincipal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FacilityScopeServiceTests {

    private final FacilityScopeService service = new FacilityScopeService();
    private final UUID facilityId = UUID.randomUUID();
    private final UUID otherFacilityId = UUID.randomUUID();

    @Test
    void staffCannotReadFacilityOutsideAssignedScope() {
        ActorPrincipal staff = actor(RoleCode.STAFF, Map.of(facilityId, FacilityScopeLevel.OPERATE));

        assertForbidden(() -> service.assertCanRead(staff, otherFacilityId));
    }

    @Test
    void operateScopeCanReadAndOperateButCannotManage() {
        ActorPrincipal staff = actor(RoleCode.STAFF, Map.of(facilityId, FacilityScopeLevel.OPERATE));

        assertThatCode(() -> service.assertCanRead(staff, facilityId)).doesNotThrowAnyException();
        assertThatCode(() -> service.assertCanOperate(staff, facilityId)).doesNotThrowAnyException();
        assertForbidden(() -> service.assertCanManage(staff, facilityId));
    }

    @Test
    void manageScopeIncludesReadAndOperateForSameFacilityOnly() {
        ActorPrincipal manager = actor(RoleCode.MANAGER, Map.of(facilityId, FacilityScopeLevel.MANAGE));

        assertThatCode(() -> service.assertCanRead(manager, facilityId)).doesNotThrowAnyException();
        assertThatCode(() -> service.assertCanOperate(manager, facilityId)).doesNotThrowAnyException();
        assertThatCode(() -> service.assertCanManage(manager, facilityId)).doesNotThrowAnyException();
        assertForbidden(() -> service.assertCanManage(manager, otherFacilityId));
    }

    @Test
    void customerCanReadButCannotOperateOrManageFacility() {
        ActorPrincipal customer = actor(RoleCode.CUSTOMER, Map.of());

        assertThatCode(() -> service.assertCanRead(customer, facilityId)).doesNotThrowAnyException();
        assertForbidden(() -> service.assertCanOperate(customer, facilityId));
        assertForbidden(() -> service.assertCanManage(customer, facilityId));
    }

    @Test
    void adminAndBusinessHaveGlobalFacilityAccess() {
        for (RoleCode role : Set.of(RoleCode.ADMIN, RoleCode.BUSINESS)) {
            ActorPrincipal actor = actor(role, Map.of());
            assertThatCode(() -> service.assertCanManage(actor, facilityId)).doesNotThrowAnyException();
        }
    }

    @Test
    void onlyStaffAndManagerAreReportedAsFacilityScoped() {
        assertThat(service.isFacilityScoped(actor(RoleCode.STAFF, Map.of()))).isTrue();
        assertThat(service.isFacilityScoped(actor(RoleCode.MANAGER, Map.of()))).isTrue();
        assertThat(service.isFacilityScoped(actor(RoleCode.CUSTOMER, Map.of()))).isFalse();
        assertThat(service.isFacilityScoped(actor(RoleCode.ADMIN, Map.of()))).isFalse();
    }

    private ActorPrincipal actor(RoleCode role, Map<UUID, FacilityScopeLevel> scopes) {
        return new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(role), Set.of(), scopes);
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(403))
            .hasMessage("The actor has no required scope for this facility");
    }
}
