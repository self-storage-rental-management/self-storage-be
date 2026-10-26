package com.storagehub.config;

import com.storagehub.domain.model.Permission;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.PermissionRepository;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.UserRepository;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class RoleDataInitializer {

    private static final int INTERNAL_POLICY_VERSION = 3;
    private static final int POLICY_OWNERSHIP_VERSION = 4;
    private static final int STAFF_RESERVATION_REVIEW_VERSION = 5;
    private static final int STAFF_TRAM_WORKFLOW_VERSION = 6;
    private static final int BUSINESS_RETURNS_VERSION = 7;
    private static final int STAFF_RENEWAL_OPERATIONS_VERSION = 8;
    private static final int STAFF_RENEWAL_PAYMENTS_VERSION = 9;
    private static final int MANAGER_UNIT_ASSIGNMENT_VERSION = 10;
    private static final int FINAL_POLICY_VERSION = 11;
    private static final int SEPARATION_OF_DUTIES_VERSION = 13;
    private static final int SETUP_EXIT_REFUND_SOD_VERSION = 15;

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public RoleDataInitializer(
        RoleRepository roleRepository,
        PermissionRepository permissionRepository,
        UserRepository userRepository
    ) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
    }

    /** Kept for focused role-policy tests that do not need user invalidation. */
    RoleDataInitializer(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this(roleRepository, permissionRepository, null);
    }

    @Bean
    @Order(10)
    CommandLineRunner seedSystemRoles() {
        return args -> {
            boolean[] securityPolicyChanged = {false};
            Map<SystemPermission, Permission> permissions = new EnumMap<>(SystemPermission.class);
            Arrays.stream(SystemPermission.values()).forEach(code -> {
                var existing = permissionRepository.findByCode(code.code());
                Permission permission = existing.orElseGet(() -> {
                    Permission created = new Permission();
                    created.setCode(code.code());
                    created.setName(code.code());
                    securityPolicyChanged[0] = true;
                    return permissionRepository.save(created);
                });
                permissions.put(code, permission);
            });

            Map<RoleCode, Role> roles = new EnumMap<>(RoleCode.class);
            Arrays.stream(RoleCode.values()).forEach(code -> {
                var existing = roleRepository.findByCode(code);
                Role role = existing.orElseGet(() -> {
                    Role created = new Role();
                    created.setCode(code);
                    created.setName(code.name());
                    securityPolicyChanged[0] = true;
                    return roleRepository.save(created);
                });
                roles.put(code, role);
            });

            // Manager must not inherit Staff's check-in permissions. Keep the
            // matrix explicit so separation of duties is visible in data.
            roles.values().forEach(role -> {
                if (role.getParentRole() != null) {
                    role.setParentRole(null);
                    securityPolicyChanged[0] = true;
                }
            });

            // Migrate the old broad defaults once. Later role edits remain
            // persistent and are protected by AdminRoleService policies.
            roles.forEach((code, role) -> {
                if (role.getPermissionsPolicyVersion() == null
                    || role.getPermissionsPolicyVersion() < INTERNAL_POLICY_VERSION) {
                    role.setPermissions(defaultPermissions(code, permissions));
                    role.setPermissionsPolicyVersion(INTERNAL_POLICY_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Upgrade only policy ownership: preserve all other customized grants.
                if (role.getPermissionsPolicyVersion() < POLICY_OWNERSHIP_VERSION) {
                    if (code == RoleCode.MANAGER || code == RoleCode.BUSINESS) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.add(permissions.get(SystemPermission.VIEW_POLICIES));
                        if (code == RoleCode.MANAGER) {
                            updated.removeIf(permission -> SystemPermission.MANAGE_POLICIES.code().equals(permission.getCode()));
                        } else {
                            updated.add(permissions.get(SystemPermission.MANAGE_POLICIES));
                        }
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(POLICY_OWNERSHIP_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Reviewing OTHER goods is a facility Staff responsibility in the
                // booking flow. Upgrade existing roles without replacing custom grants.
                if (role.getPermissionsPolicyVersion() < STAFF_RESERVATION_REVIEW_VERSION) {
                    if (code == RoleCode.STAFF) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.add(permissions.get(SystemPermission.APPROVE_RESERVATIONS));
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(STAFF_RESERVATION_REVIEW_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // The confirmed-reservation handover workflow used to grant
                // physical unit assignment to Staff. Keep this historical
                // upgrade for databases that have not reached policy version 6.
                if (role.getPermissionsPolicyVersion() < STAFF_TRAM_WORKFLOW_VERSION) {
                    if (code == RoleCode.STAFF) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.add(permissions.get(SystemPermission.APPROVE_RESERVATIONS));
                        updated.add(permissions.get(SystemPermission.ASSIGN_UNITS));
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(STAFF_TRAM_WORKFLOW_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Allow Business Operations role to oversee warehouse returns across all facilities.
                if (role.getPermissionsPolicyVersion() < BUSINESS_RETURNS_VERSION) {
                    if (code == RoleCode.BUSINESS) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.add(permissions.get(SystemPermission.VIEW_RETURNS));
                        updated.add(permissions.get(SystemPermission.MANAGE_RENTALS));
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(BUSINESS_RETURNS_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Staff operates Renewal assignments and signing appointments.
                // Add the existing capability to persisted Staff roles without replacing custom grants.
                if (role.getPermissionsPolicyVersion() < STAFF_RENEWAL_OPERATIONS_VERSION) {
                    if (code == RoleCode.STAFF) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.add(permissions.get(SystemPermission.MANAGE_RENTALS));
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(STAFF_RENEWAL_OPERATIONS_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Staff records the remaining Renewal payment against an authoritative
                // statement and receipt before completing the signing workflow.
                if (role.getPermissionsPolicyVersion() < STAFF_RENEWAL_PAYMENTS_VERSION) {
                    if (code == RoleCode.STAFF) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.add(permissions.get(SystemPermission.VIEW_PAYMENTS));
                        updated.add(permissions.get(SystemPermission.MANAGE_PAYMENTS));
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(STAFF_RENEWAL_PAYMENTS_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Physical unit assignment belongs to the Manager workflow.
                // Revoke the old Staff grant from persisted roles as well as
                // from newly seeded defaults.
                if (role.getPermissionsPolicyVersion() < MANAGER_UNIT_ASSIGNMENT_VERSION) {
                    if (code == RoleCode.STAFF) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.remove(permissions.get(SystemPermission.ASSIGN_UNITS));
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(MANAGER_UNIT_ASSIGNMENT_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Apply the final matrix once. Later Admin edits remain
                // persistent because the policy version is already current.
                if (role.getPermissionsPolicyVersion() < FINAL_POLICY_VERSION) {
                    role.setPermissions(defaultPermissions(code, permissions));
                    role.setPermissionsPolicyVersion(FINAL_POLICY_VERSION);
                    securityPolicyChanged[0] = true;
                }
                // Manager supervises return disputes but does not perform the
                // Staff inspection/settlement operation. Staff executes the
                // approved refund; Business only decides the approved amount.
                if (role.getPermissionsPolicyVersion() < SEPARATION_OF_DUTIES_VERSION) {
                    if (code == RoleCode.MANAGER) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.remove(permissions.get(SystemPermission.PROCESS_RETURNS));
                        updated.remove(permissions.get(SystemPermission.EXECUTE_REFUNDS));
                        updated.remove(permissions.get(SystemPermission.PERFORM_CHECKIN));
                        updated.remove(permissions.get(SystemPermission.OPERATE_RENEWALS));
                        updated.remove(permissions.get(SystemPermission.MANAGE_SUPPORT));
                        role.setPermissions(updated);
                    } else if (code == RoleCode.STAFF) {
                        var updated = new HashSet<>(role.getPermissions());
                        updated.add(permissions.get(SystemPermission.EXECUTE_REFUNDS));
                        role.setPermissions(updated);
                    }
                    role.setPermissionsPolicyVersion(SEPARATION_OF_DUTIES_VERSION);
                    securityPolicyChanged[0] = true;
                }
                if (role.getPermissionsPolicyVersion() < SETUP_EXIT_REFUND_SOD_VERSION) {
                    var updated = new HashSet<>(role.getPermissions());
                    if (code == RoleCode.BUSINESS) {
                        updated.add(permissions.get(SystemPermission.APPROVE_REFUNDS));
                        updated.remove(permissions.get(SystemPermission.MANAGE_SETTINGS));
                        updated.remove(permissions.get(SystemPermission.EXECUTE_REFUNDS));
                        updated.remove(permissions.get(SystemPermission.APPROVE_RESERVATIONS));
                        updated.remove(permissions.get(SystemPermission.ASSIGN_UNITS));
                        updated.remove(permissions.get(SystemPermission.PERFORM_CHECKIN));
                        updated.remove(permissions.get(SystemPermission.PROCESS_RETURNS));
                        updated.remove(permissions.get(SystemPermission.MANAGE_RENTALS));
                        updated.remove(permissions.get(SystemPermission.MANAGE_PAYMENTS));
                        updated.remove(permissions.get(SystemPermission.CONFIRM_PAYMENTS));
                        updated.remove(permissions.get(SystemPermission.OPERATE_RENEWALS));
                        updated.remove(permissions.get(SystemPermission.MANAGE_INVENTORY));
                        updated.remove(permissions.get(SystemPermission.MANAGE_STAFF_TASKS));
                        updated.remove(permissions.get(SystemPermission.MANAGE_SUPPORT));
                    } else if (code == RoleCode.STAFF) {
                        updated.add(permissions.get(SystemPermission.EXECUTE_REFUNDS));
                    }
                    role.setPermissions(updated);
                    role.setPermissionsPolicyVersion(SETUP_EXIT_REFUND_SOD_VERSION);
                    securityPolicyChanged[0] = true;
                }
            });
            roleRepository.saveAll(roles.values());
            if (securityPolicyChanged[0] && userRepository != null) {
                var activeUsers = userRepository.findByStatus(com.storagehub.domain.model.UserStatus.ACTIVE);
                activeUsers.forEach(user ->
                    user.setAuthzVersion(user.getAuthzVersion() + 1)
                );
                userRepository.saveAll(activeUsers);
            }
        };
    }

    private Set<Permission> defaultPermissions(
        RoleCode roleCode,
        Map<SystemPermission, Permission> permissions
    ) {
        return switch (roleCode) {
            case ADMIN -> permissionSet(permissions,
                SystemPermission.VIEW_DASHBOARD,
                SystemPermission.MANAGE_USERS,
                SystemPermission.MANAGE_ROLES,
                SystemPermission.MANAGE_SETTINGS,
                SystemPermission.VIEW_AUDIT_LOGS,
                SystemPermission.MANAGE_SESSIONS
            );
            case MANAGER -> permissionSet(permissions,
                SystemPermission.VIEW_DASHBOARD,
                SystemPermission.VIEW_FACILITIES,
                SystemPermission.VIEW_UNITS,
                SystemPermission.VIEW_RESERVATIONS,
                SystemPermission.APPROVE_RESERVATIONS,
                SystemPermission.ASSIGN_UNITS,
                SystemPermission.VIEW_CONTRACTS,
                SystemPermission.VIEW_CHECKINS,
                SystemPermission.VIEW_RENTALS,
                SystemPermission.MANAGE_RENTALS,
                SystemPermission.VIEW_RETURNS,
                SystemPermission.VIEW_PAYMENTS,
                SystemPermission.CONFIRM_PAYMENTS,
                SystemPermission.MANAGE_PAYMENTS,
                SystemPermission.APPROVE_REFUNDS,
                SystemPermission.WAIVE_FEES,
                SystemPermission.VIEW_POLICIES,
                SystemPermission.MANAGE_INVENTORY,
                SystemPermission.MANAGE_STAFF_TASKS,
                SystemPermission.VIEW_SUPPORT,
                SystemPermission.VIEW_REPORTS
            );
            case STAFF -> permissionSet(permissions,
                SystemPermission.VIEW_DASHBOARD,
                SystemPermission.VIEW_FACILITIES,
                SystemPermission.VIEW_UNITS,
                SystemPermission.VIEW_RESERVATIONS,
                SystemPermission.APPROVE_RESERVATIONS,
                SystemPermission.VIEW_CONTRACTS,
                SystemPermission.VIEW_CHECKINS,
                SystemPermission.PERFORM_CHECKIN,
                SystemPermission.VIEW_RENTALS,
                SystemPermission.VIEW_PAYMENTS,
                SystemPermission.MANAGE_PAYMENTS,
                SystemPermission.VIEW_RETURNS,
                SystemPermission.PROCESS_RETURNS,
                SystemPermission.EXECUTE_REFUNDS,
                SystemPermission.OPERATE_RENEWALS,
                SystemPermission.VIEW_SUPPORT,
                SystemPermission.MANAGE_SUPPORT
            );
            case BUSINESS -> permissionSet(permissions,
                SystemPermission.MANAGE_POLICIES,
                SystemPermission.VIEW_DASHBOARD,
                SystemPermission.VIEW_FACILITIES,
                SystemPermission.VIEW_UNITS,
                SystemPermission.VIEW_RESERVATIONS,
                SystemPermission.VIEW_CONTRACTS,
                SystemPermission.VIEW_RENTALS,
                SystemPermission.VIEW_RETURNS,
                SystemPermission.VIEW_PAYMENTS,
                SystemPermission.APPROVE_REFUNDS,
                SystemPermission.WAIVE_FEES,
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
                SystemPermission.VIEW_POLICIES,
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
