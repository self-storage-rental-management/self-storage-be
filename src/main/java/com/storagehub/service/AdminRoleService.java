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
    private final AdminAuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<AdminRoleResponse> list(ActorPrincipal actor) {
        authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
        return roleRepository.findAll().stream()
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
        if (roleCode == RoleCode.ADMIN
            && (!permissionCodes.contains(SystemPermission.MANAGE_USERS.code())
                || !permissionCodes.contains(SystemPermission.MANAGE_ROLES.code()))) {
            throw ApiExceptions.conflict("ADMIN must retain manage_users and manage_roles permissions");
        }

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
        return new AdminRoleResponse(
            role.getCode(),
            role.getName(),
            role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toUnmodifiableSet())
        );
    }
}
