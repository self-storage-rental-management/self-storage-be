package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.storagehub.api.admin.AdminUpdateRolePermissionsRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyOwnershipAuthorizationTests {
    private ActorPrincipal actor(RoleCode role) {
        // Include a stale policy-write grant: the role gate must still protect policy.
        return new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(role),
            Set.of("policies:read", "policies:update", "roles:manage"), Map.of());
    }

    @Test void managerCanReadButCannotMutateAnyPolicyEvenWithStaleWritePermission() {
        var policies = mock(RentalPackagePolicyRepository.class);
        var facilities = mock(FacilityRepository.class);
        var settings = mock(SystemSettingRepository.class);
        var audit = mock(AuditLogService.class);
        var service = new BusinessPolicyService(policies, facilities, settings,
            new AdminAuthorizationService(), mock(FacilityScopeService.class), audit);
        var manager = actor(RoleCode.MANAGER);
        assertThat(service.getBusinessConfig(manager)).isNotNull();
        clearInvocations(settings);
        assertForbidden(() -> service.updateBusinessConfig(manager, null));
        assertForbidden(() -> service.createPackagePolicy(manager, null));
        assertForbidden(() -> service.updatePackagePolicy(manager, UUID.randomUUID(), null));
        assertForbidden(() -> service.deletePackagePolicy(manager, UUID.randomUUID()));
        verifyNoInteractions(policies, facilities, settings, audit);
    }

    @Test void adminCannotGivePolicyWritesToManagerOrRemoveBusinessOwnership() {
        var roles = mock(RoleRepository.class);
        var permissions = mock(PermissionRepository.class);
        var audit = mock(AuditLogService.class);
        for (var code : List.of(RoleCode.MANAGER, RoleCode.BUSINESS)) {
            var role = new Role(); role.setCode(code);
            when(roles.findByCode(code)).thenReturn(Optional.of(role));
        }
        var service = new AdminRoleService(roles, permissions, mock(UserRepository.class),
            new AdminAuthorizationService(), audit);
        var managerReads = Set.of("reservations:approve", "policies:read", "staff_tasks:update", "reports:read");
        var illegalManager = new HashSet<>(managerReads); illegalManager.add("policies:update");
        assertConflict(() -> service.updatePermissions(actor(RoleCode.ADMIN), RoleCode.MANAGER,
            new AdminUpdateRolePermissionsRequest(illegalManager)));
        assertConflict(() -> service.updatePermissions(actor(RoleCode.ADMIN), RoleCode.BUSINESS,
            new AdminUpdateRolePermissionsRequest(Set.of("policies:read"))));
        verifyNoInteractions(permissions, audit);
        verify(roles, never()).saveAndFlush(any());
    }

    @Test void adminCanKeepManagerReadOnlyAndBusinessAsPolicyOwner() {
        var roles = mock(RoleRepository.class);
        var permissions = mock(PermissionRepository.class);
        when(permissions.findByCode(any())).thenAnswer(call -> {
            var p = new Permission(); p.setCode(call.getArgument(0)); return Optional.of(p);
        });
        when(roles.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var service = new AdminRoleService(roles, permissions, mock(UserRepository.class),
            new AdminAuthorizationService(), mock(AuditLogService.class));
        for (var code : List.of(RoleCode.MANAGER, RoleCode.BUSINESS)) {
            var role = new Role(); role.setCode(code);
            when(roles.findByCode(code)).thenReturn(Optional.of(role));
            Set<String> grants = code == RoleCode.MANAGER
                ? Set.of("reservations:approve", "policies:read", "staff_tasks:update", "reports:read")
                : Set.of("policies:read", "policies:update");
            var response = service.updatePermissions(actor(RoleCode.ADMIN), code, new AdminUpdateRolePermissionsRequest(grants));
            assertThat(response.permissions()).containsExactlyInAnyOrderElementsOf(grants);
        }
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }
    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }
}
