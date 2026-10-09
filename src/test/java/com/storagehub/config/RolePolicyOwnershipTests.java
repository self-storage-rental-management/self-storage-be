package com.storagehub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PermissionRepository;
import com.storagehub.domain.repo.RoleRepository;
import java.util.*;
import org.junit.jupiter.api.Test;

class RolePolicyOwnershipTests {
    @Test void upgradesOnlyPolicyOwnershipAndPreservesCustomGrantsAcrossRestarts() throws Exception {
        var roles = new EnumMap<RoleCode, Role>(RoleCode.class);
        var permissions = new EnumMap<SystemPermission, Permission>(SystemPermission.class);
        var roleRepository = mock(RoleRepository.class);
        var permissionRepository = mock(PermissionRepository.class);
        for (var code : SystemPermission.values()) {
            var permission = new Permission(); permission.setCode(code.code());
            permissions.put(code, permission);
            when(permissionRepository.findByCode(code.code())).thenReturn(Optional.of(permission));
        }
        for (var code : RoleCode.values()) {
            var role = new Role(); role.setCode(code); role.setPermissionsPolicyVersion(3);
            // A customized grant deliberately differs from built-in defaults.
            role.setPermissions(new HashSet<>(Set.of(permissions.get(SystemPermission.VIEW_UNITS))));
            roles.put(code, role);
            when(roleRepository.findByCode(code)).thenReturn(Optional.of(role));
        }
        var manager = roles.get(RoleCode.MANAGER);
        manager.getPermissions().add(permissions.get(SystemPermission.MANAGE_POLICIES));
        var runner = new RoleDataInitializer(roleRepository, permissionRepository).seedSystemRoles();
        runner.run();
        runner.run();
        assertThat(manager.getPermissions()).extracting(Permission::getCode)
            .containsExactlyInAnyOrder("storage_units:read", "policies:read");
        assertThat(roles.get(RoleCode.BUSINESS).getPermissions()).extracting(Permission::getCode)
            .containsExactlyInAnyOrder("storage_units:read", "policies:read", "policies:update");
        assertThat(roles.get(RoleCode.STAFF).getPermissions()).extracting(Permission::getCode)
            .containsExactly("storage_units:read");
        assertThat(roles.values()).allSatisfy(role -> assertThat(role.getPermissionsPolicyVersion()).isEqualTo(4));
    }

    @Test void newRolesGrantPolicyWritesOnlyToBusinessOperations() throws Exception {
        var roles = new EnumMap<RoleCode, Role>(RoleCode.class);
        var roleRepository = mock(RoleRepository.class);
        var permissionRepository = mock(PermissionRepository.class);
        when(permissionRepository.findByCode(any())).thenReturn(Optional.empty());
        when(permissionRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(roleRepository.findByCode(any())).thenReturn(Optional.empty());
        when(roleRepository.save(any())).thenAnswer(call -> {
            Role role = call.getArgument(0); roles.put(role.getCode(), role); return role;
        });
        new RoleDataInitializer(roleRepository, permissionRepository).seedSystemRoles().run();
        for (var entry : roles.entrySet()) {
            assertThat(entry.getValue().getEffectivePermissions().stream()
                .anyMatch(permission -> "policies:update".equals(permission.getCode())))
                .as("%s policy ownership", entry.getKey()).isEqualTo(entry.getKey() == RoleCode.BUSINESS);
        }
        assertThat(roles.get(RoleCode.MANAGER).getEffectivePermissions()).extracting(Permission::getCode)
            .contains("policies:read", "checkins:process");
    }
}
