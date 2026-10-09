package com.storagehub.config;

import com.storagehub.domain.model.Permission;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.PermissionRepository;
import com.storagehub.domain.repo.RoleRepository;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@RequiredArgsConstructor
public class RoleDataInitializer {

    private static final int INTERNAL_POLICY_VERSION = 3;

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    @Bean
    @Order(10)
    CommandLineRunner seedSystemRoles() {
        return args -> {
            Map<SystemPermission, Permission> permissions = new EnumMap<>(SystemPermission.class);
            Arrays.stream(SystemPermission.values()).forEach(code -> {
                Permission permission = permissionRepository.findByCode(code.code()).orElseGet(() -> {
                    Permission created = new Permission();
                    created.setCode(code.code());
                    created.setName(code.code());
                    return permissionRepository.save(created);
                });
                permissions.put(code, permission);
            });

            Map<RoleCode, Role> roles = new EnumMap<>(RoleCode.class);
            Arrays.stream(RoleCode.values()).forEach(code -> {
                Role role = roleRepository.findByCode(code).orElseGet(() -> {
                    Role created = new Role();
                    created.setCode(code);
                    created.setName(code.name());
                    return roleRepository.save(created);
                });
                roles.put(code, role);
            });

            // A manager is a staff member with additional approval,
            // coordination, policy and reporting responsibilities.
            roles.get(RoleCode.MANAGER).setParentRole(roles.get(RoleCode.STAFF));
            roles.get(RoleCode.STAFF).setParentRole(null);
            roles.values().stream()
                .filter(role -> role.getCode() != RoleCode.MANAGER && role.getCode() != RoleCode.STAFF)
                .forEach(role -> role.setParentRole(null));

            // Migrate the old broad defaults once. Later role edits remain
            // persistent and are protected by AdminRoleService policies.
            roles.forEach((code, role) -> {
                if (role.getPermissionsPolicyVersion() == null
                    || role.getPermissionsPolicyVersion() < INTERNAL_POLICY_VERSION) {
                    role.setPermissions(defaultPermissions(code, permissions));
                    role.setPermissionsPolicyVersion(INTERNAL_POLICY_VERSION);
                }
            });
            roleRepository.saveAll(roles.values());
        };
    }

    private Set<Permission> defaultPermissions(
        RoleCode roleCode,
        Map<SystemPermission, Permission> permissions
    ) {
        return switch (roleCode) {
            case ADMIN -> permissionSet(permissions,
                SystemPermission.MANAGE_USERS,
                SystemPermission.MANAGE_ROLES,
                SystemPermission.MANAGE_SETTINGS,
                SystemPermission.VIEW_AUDIT_LOGS
            );
            case MANAGER -> permissionSet(permissions,
                SystemPermission.APPROVE_RESERVATIONS,
                SystemPermission.ASSIGN_UNITS,
                SystemPermission.VIEW_PAYMENTS,
                SystemPermission.VIEW_POLICIES,
                SystemPermission.MANAGE_PAYMENTS,
                SystemPermission.MANAGE_INVENTORY,
                SystemPermission.MANAGE_POLICIES,
                SystemPermission.MANAGE_STAFF_TASKS,
                SystemPermission.VIEW_SUPPORT,
                SystemPermission.MANAGE_SUPPORT,
                SystemPermission.VIEW_REPORTS
            );
            case STAFF -> permissionSet(permissions,
                SystemPermission.VIEW_DASHBOARD,
                SystemPermission.VIEW_FACILITIES,
                SystemPermission.VIEW_UNITS,
                SystemPermission.VIEW_RESERVATIONS,
                SystemPermission.VIEW_CONTRACTS,
                SystemPermission.VIEW_CHECKINS,
                SystemPermission.PERFORM_CHECKIN,
                SystemPermission.VIEW_RENTALS,
                SystemPermission.VIEW_RETURNS,
                SystemPermission.PROCESS_RETURNS,
                SystemPermission.VIEW_SUPPORT,
                SystemPermission.MANAGE_SUPPORT
            );
            case BUSINESS -> permissionSet(permissions,
                SystemPermission.VIEW_DASHBOARD,
                SystemPermission.VIEW_FACILITIES,
                SystemPermission.VIEW_UNITS,
                SystemPermission.VIEW_RESERVATIONS,
                SystemPermission.VIEW_CONTRACTS,
                SystemPermission.VIEW_RENTALS,
                SystemPermission.VIEW_PAYMENTS,
                SystemPermission.VIEW_POLICIES,
                SystemPermission.VIEW_SUPPORT,
                SystemPermission.VIEW_REPORTS
            );
            case CUSTOMER -> permissionSet(permissions,
                SystemPermission.VIEW_DASHBOARD,
                SystemPermission.VIEW_FACILITIES,
                SystemPermission.VIEW_UNITS,
                SystemPermission.BOOK_STORAGE,
                SystemPermission.VIEW_RESERVATIONS,
                SystemPermission.VIEW_CONTRACTS,
                SystemPermission.VIEW_RENTALS,
                SystemPermission.VIEW_PAYMENTS,
                SystemPermission.VIEW_SUPPORT
            );
        };
    }

    private Set<Permission> permissionSet(
        Map<SystemPermission, Permission> permissions,
        SystemPermission... codes
    ) {
        Set<Permission> result = new HashSet<>();
        for (SystemPermission code : codes) {
            result.add(permissions.get(code));
        }
        return result;
    }
}
