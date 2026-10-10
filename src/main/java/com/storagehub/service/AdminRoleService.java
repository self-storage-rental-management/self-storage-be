package com.storagehub.service;

import com.storagehub.api.admin.AdminRoleResponse;
import com.storagehub.api.admin.AdminUpdateRolePermissionsRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Permission;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.PermissionRepository;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<AdminRoleResponse> list(ActorPrincipal actor) {
        authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
        return roleRepository.findAll().stream()
            .filter(role -> role.getCode() != RoleCode.CUSTOMER)
            .sorted(Comparator.comparing(Role::getCode))
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public AdminRoleResponse updatePermissions(
        ActorPrincipal actor,
        RoleCode roleCode,
        AdminUpdateRolePermissionsRequest request
    ) {
        authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
        Role role = roleRepository.findByCode(roleCode)
            .orElseThrow(() -> ApiExceptions.notFound("The requested role was not found"));
        Set<String> permissionCodes = normalizePermissionCodes(request.permissions());
        validateInternalRolePolicy(roleCode, permissionCodes);

        AdminRoleResponse before = toResponse(role);
        role.setPermissions(resolvePermissions(permissionCodes));
        Role saved = roleRepository.saveAndFlush(role);
        AdminRoleResponse after = toResponse(saved);
        auditLogService.recordMutation("ADMIN_ROLE_PERMISSIONS_UPDATED", "Role", saved.getId(), null, before, after);
        return after;
    }

    private Set<String> normalizePermissionCodes(Set<String> requested) {
        if (requested == null) {
            throw ApiExceptions.validation("permissions must not be null", null);
        }
        Set<String> validCodes = Arrays.stream(SystemPermission.values())
            .map(SystemPermission::code)
            .collect(Collectors.toUnmodifiableSet());
        Set<String> normalized = requested.stream()
            .map(value -> value == null ? "" : value.trim())
            .collect(Collectors.toCollection(HashSet::new));
        if (normalized.stream().anyMatch(value -> !validCodes.contains(value))) {
            throw ApiExceptions.validation("One or more permissions are not supported", normalized);
        }
        return Set.copyOf(normalized);
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        Set<Permission> permissions = new HashSet<>();
        for (String code : codes) {
            permissions.add(permissionRepository.findByCode(code)
                .orElseThrow(() -> ApiExceptions.conflict("The requested permission is not initialized: " + code)));
        }
        return permissions;
    }

    private AdminRoleResponse toResponse(Role role) {
        Set<String> directPermissions = role.getPermissions().stream()
            .map(Permission::getCode)
            .collect(Collectors.toUnmodifiableSet());
        Set<String> effectivePermissions = role.getEffectivePermissions().stream()
            .map(Permission::getCode)
            .collect(Collectors.toUnmodifiableSet());
        Set<String> inheritedPermissions = effectivePermissions.stream()
            .filter(permission -> !directPermissions.contains(permission))
            .collect(Collectors.toUnmodifiableSet());
        return new AdminRoleResponse(
            role.getCode(),
            role.getName(),
            role.getParentRole() == null ? null : role.getParentRole().getCode(),
            effectivePermissions,
            directPermissions,
            inheritedPermissions,
            userRepository.countByRoles_Code(role.getCode())
        );
    }

    private void validateInternalRolePolicy(RoleCode roleCode, Set<String> permissions) {
        if (roleCode == RoleCode.CUSTOMER) {
            throw ApiExceptions.forbidden("Customer access is managed by the customer portal policy");
        }

        Set<String> adminOnly = Set.of(
            SystemPermission.MANAGE_USERS.code(),
            SystemPermission.MANAGE_ROLES.code(),
            SystemPermission.MANAGE_SETTINGS.code(),
            SystemPermission.VIEW_AUDIT_LOGS.code()
        );
        if (roleCode == RoleCode.ADMIN && !permissions.equals(adminOnly)) {
            throw ApiExceptions.conflict("Admin permissions are limited to account, role, system and audit administration");
        }

        if (roleCode == RoleCode.MANAGER) {
            Set<String> required = Set.of(
                SystemPermission.APPROVE_RESERVATIONS.code(),
                SystemPermission.VIEW_POLICIES.code(),
                SystemPermission.MANAGE_STAFF_TASKS.code(),
                SystemPermission.VIEW_REPORTS.code()
            );
            if (!permissions.containsAll(required)) {
                throw ApiExceptions.conflict("Manager must retain approval, policy-read, task and reporting permissions");
            }
            if (permissions.contains(SystemPermission.MANAGE_POLICIES.code())) {
                throw ApiExceptions.conflict("Manager may read policies; only Business Operations may edit them");
            }
        }

        if (roleCode == RoleCode.STAFF && intersects(permissions, Set.of(
            SystemPermission.APPROVE_RESERVATIONS.code(),
            SystemPermission.MANAGE_PAYMENTS.code(),
            SystemPermission.MANAGE_POLICIES.code(),
            SystemPermission.MANAGE_STAFF_TASKS.code()
        ))) {
            throw ApiExceptions.conflict("Staff cannot approve requests, manage fees, collect payments or coordinate staff tasks");
        }

        if (roleCode == RoleCode.BUSINESS && !permissions.containsAll(Set.of(
            SystemPermission.VIEW_POLICIES.code(), SystemPermission.MANAGE_POLICIES.code()
        ))) {
            throw ApiExceptions.conflict("Business Operations must retain policy read and edit permissions");
        }
        if (roleCode == RoleCode.BUSINESS && intersects(permissions, Set.of(
            SystemPermission.MANAGE_SETTINGS.code(),
            SystemPermission.MANAGE_PAYMENTS.code(),
            SystemPermission.APPROVE_RESERVATIONS.code()
        ))) {
            throw ApiExceptions.conflict("Business Operations cannot manage system settings, collect payments or approve reservations");
        }
    }

    private boolean intersects(Set<String> actual, Set<String> forbidden) {
        return actual.stream().anyMatch(forbidden::contains);
    }
}
