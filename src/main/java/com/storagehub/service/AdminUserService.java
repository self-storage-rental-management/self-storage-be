package com.storagehub.service;

import com.storagehub.api.admin.AdminCreateUserRequest;
import com.storagehub.api.admin.AdminPasswordResetRequest;
import com.storagehub.api.admin.AdminPatchUserRequest;
import com.storagehub.api.admin.AdminStatusUpdateRequest;
import com.storagehub.api.admin.AdminUpdateFacilitiesRequest;
import com.storagehub.api.admin.AdminUpdateRolesRequest;
import com.storagehub.api.admin.AdminUserResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserFacilityScope;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.SessionRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final FacilityRepository facilityRepository;
    private final UserFacilityScopeRepository scopeRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final com.storagehub.service.email.EmailService emailService;

    private static final String TEMP_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String TEMP_LOWER = "abcdefghjkmnpqrstuvwxyz";
    private static final String TEMP_DIGITS = "23456789";
    private static final String TEMP_SPECIAL = "@#$%!*?";
    private static final String TEMP_ALL = TEMP_UPPER + TEMP_LOWER + TEMP_DIGITS + TEMP_SPECIAL;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(
        ActorPrincipal actor,
        int page,
        int size,
        String search,
        RoleCode role,
        UserStatus status,
        UUID facilityId,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdminUserResponse> result = userRepository.search(
            clean(search), role, status, facilityId, pageable
        ).map(this::toResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(ActorPrincipal actor, UUID id) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        return toResponse(requiredUser(id));
    }

    @Transactional
    public AdminUserResponse create(ActorPrincipal actor, AdminCreateUserRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiExceptions.conflict("An account with this email already exists");
        }

        Set<RoleCode> roleCodes = normalizeRoles(request.roles());
        if (!(roleCodes.size() == 1 && roleCodes.contains(RoleCode.CUSTOMER))) {
            authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
        }
        Map<UUID, FacilityScopeLevel> scopes = normalizeScopes(request.facilityScopes());
        validateFacilityAssignment(roleCodes, scopes);

        String temporaryPassword;
        if (request.password() != null && !request.password().isBlank()) {
            temporaryPassword = request.password();
        } else {
            temporaryPassword = generateSecureTemporaryPassword();
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setFullName(requireText(request.fullName(), "fullName"));
        user.setPhone(normalizeNullable(request.phone()));
        user.setStatus(UserStatus.ACTIVE);
        user.setMustChangePassword(true);
        user.setRoles(resolveRoles(roleCodes));
        User saved = userRepository.saveAndFlush(user);
        replaceScopes(saved, scopes);

        AdminUserResponse response = toResponse(saved);
        auditLogService.recordMutation("ADMIN_USER_CREATED", "User", saved.getId(), null, null, response);

        String createdAt = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.now());
        emailService.sendAccountCreated(
            saved.getId(),
            saved.getEmail(),
            saved.getFullName(),
            createdAt,
            temporaryPassword,
            null
        );

        return response;
    }

    private String generateSecureTemporaryPassword() {
        StringBuilder sb = new StringBuilder(12);
        sb.append(TEMP_UPPER.charAt(secureRandom.nextInt(TEMP_UPPER.length())));
        sb.append(TEMP_LOWER.charAt(secureRandom.nextInt(TEMP_LOWER.length())));
        sb.append(TEMP_DIGITS.charAt(secureRandom.nextInt(TEMP_DIGITS.length())));
        sb.append(TEMP_SPECIAL.charAt(secureRandom.nextInt(TEMP_SPECIAL.length())));
        for (int i = 4; i < 12; i++) {
            sb.append(TEMP_ALL.charAt(secureRandom.nextInt(TEMP_ALL.length())));
        }
        List<Character> chars = new ArrayList<>(12);
        for (char c : sb.toString().toCharArray()) {
            chars.add(c);
        }
        Collections.shuffle(chars, secureRandom);
        StringBuilder shuffled = new StringBuilder(12);
        for (char c : chars) {
            shuffled.append(c);
        }
        return shuffled.toString();
    }

    @Transactional
    public AdminUserResponse update(ActorPrincipal actor, UUID id, AdminPatchUserRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        if (request.isEmpty()) {
            throw ApiExceptions.validation("At least one user field must be provided", null);
        }

        User user = requiredUser(id);
        assertEditableTarget(user);
        AdminUserResponse before = toResponse(user);
        if (request.roles() != null || request.facilityScopes() != null) {
            assertNotCustomerSelfMutation(actor, user);
        }
        boolean securityChanged = false;
        if (request.email() != null) {
            String email = normalizeEmail(request.email());
            if (!email.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmailIgnoreCase(email)) {
                throw ApiExceptions.conflict("An account with this email already exists");
            }
            user.setEmail(email);
        }
        if (request.fullName() != null) {
            user.setFullName(requireText(request.fullName(), "fullName"));
        }
        if (request.phone() != null) {
            user.setPhone(normalizeNullable(request.phone()));
        }
        if (request.roles() != null) {
            authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
            user.setRoles(resolveRoles(validateRoleAssignment(actor, user, request.roles())));
            securityChanged = true;
        }
        if (request.facilityScopes() != null) {
            authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
            validateFacilityAssignment(user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet()), request.facilityScopes());
            securityChanged = true;
        }

        User saved = userRepository.saveAndFlush(user);
        if (request.facilityScopes() != null) {
            replaceScopes(saved, normalizeScopes(request.facilityScopes()));
        }
        if (securityChanged) {
            revokeSessions(saved);
        }

        AdminUserResponse response = toResponse(saved);
        auditLogService.recordMutation("ADMIN_USER_UPDATED", "User", saved.getId(), null, before, response);

        String changeTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.now());

        if (request.email() != null && !saved.getEmail().equalsIgnoreCase(before.email())) {
            emailService.sendAccountChanged(
                saved.getId(), before.email(), saved.getFullName(), changeTime, "Email",
                before.email(), saved.getEmail(), true, null, null, null
            );
            emailService.sendAccountChanged(
                saved.getId(), saved.getEmail(), saved.getFullName(), changeTime, "Email",
                before.email(), saved.getEmail(), true, null, null, null
            );
        }

        if (request.phone() != null && !java.util.Objects.equals(request.phone(), before.phone())) {
            emailService.sendAccountChanged(
                saved.getId(), saved.getEmail(), saved.getFullName(), changeTime, "Số điện thoại",
                before.phone() != null ? before.phone() : "Chưa thiết lập",
                saved.getPhone() != null ? saved.getPhone() : "Đã xóa",
                true, null, null, null
            );
        }

        if (request.roles() != null) {
            String oldRoles = before.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
            String newRoles = response.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
            if (!oldRoles.equals(newRoles)) {
                emailService.sendAccountChanged(
                    saved.getId(), saved.getEmail(), saved.getFullName(), changeTime, "Vai trò",
                    oldRoles, newRoles, true, null, null, null
                );
            }
        }

        return response;
    }

    @Transactional
    public AdminUserResponse updateRoles(ActorPrincipal actor, UUID id, AdminUpdateRolesRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
        User user = requiredUser(id);
        assertEditableTarget(user);
        AdminUserResponse before = toResponse(user);
        assertNotCustomerSelfMutation(actor, user);
        Set<RoleCode> roleCodes = validateRoleAssignment(actor, user, request.roles());
        user.setRoles(resolveRoles(roleCodes));
        User saved = userRepository.saveAndFlush(user);
        validateFacilityAssignment(roleCodes, currentScopeMap(saved));
        revokeSessions(saved);

        AdminUserResponse response = toResponse(saved);
        auditLogService.recordMutation("ADMIN_USER_ROLES_UPDATED", "User", saved.getId(), null, before, response);

        String changeTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.now());
        String oldRoles = before.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        String newRoles = response.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        if (!oldRoles.equals(newRoles)) {
            emailService.sendAccountChanged(
                saved.getId(), saved.getEmail(), saved.getFullName(), changeTime, "Vai trò",
                oldRoles, newRoles, true, null, null, null
            );
        }

        return response;
    }

    @Transactional
    public AdminUserResponse updateFacilities(ActorPrincipal actor, UUID id, AdminUpdateFacilitiesRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        authorizationService.require(actor, SystemPermission.MANAGE_ROLES);
        User user = requiredUser(id);
        assertEditableTarget(user);
        AdminUserResponse before = toResponse(user);
        assertNotCustomerSelfMutation(actor, user);
        Map<UUID, FacilityScopeLevel> scopes = normalizeScopes(request.facilityScopes());
        validateFacilityAssignment(roleCodes(user), scopes);
        replaceScopes(user, scopes);
        User saved = userRepository.saveAndFlush(user);
        revokeSessions(saved);

        AdminUserResponse response = toResponse(saved);
        auditLogService.recordMutation("ADMIN_USER_FACILITY_SCOPES_UPDATED", "User", saved.getId(), null, before, response);
        return response;
    }

    @Transactional
    public AdminUserResponse updateStatus(ActorPrincipal actor, UUID id, AdminStatusUpdateRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        User user = requiredUser(id);
        assertEditableTarget(user);
        AdminUserResponse before = toResponse(user);
        user.setStatus(request.status());
        User saved = userRepository.saveAndFlush(user);
        revokeSessions(saved);

        AdminUserResponse response = toResponse(saved);
        auditLogService.recordMutation("ADMIN_USER_STATUS_CHANGED", "User", saved.getId(), null, before, response);
        return response;
    }

    @Transactional
    public AdminUserResponse unlock(ActorPrincipal actor, UUID id) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        User user = requiredUser(id);
        assertEditableTarget(user);
        if (user.getStatus() != UserStatus.LOCKED) {
            throw ApiExceptions.conflict("Only LOCKED accounts can be unlocked");
        }

        AdminUserResponse before = toResponse(user);
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.saveAndFlush(user);
        revokeSessions(saved);

        AdminUserResponse response = toResponse(saved);
        auditLogService.recordMutation("UNLOCK_ACCOUNT", "User", saved.getId(), null, before, response);
        return response;
    }

    @Transactional
    public AdminUserResponse resetPassword(ActorPrincipal actor, UUID id, AdminPasswordResetRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        User user = requiredUser(id);
        assertEditableTarget(user);
        boolean wasRequiredToChangePassword = user.isMustChangePassword();

        String temporaryPassword;
        if (request.temporaryPassword() != null && !request.temporaryPassword().isBlank()) {
            temporaryPassword = request.temporaryPassword();
        } else {
            temporaryPassword = generateSecureTemporaryPassword();
        }

        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        User saved = userRepository.saveAndFlush(user);
        revokeSessions(saved);

        AdminUserResponse response = toResponse(saved);
        auditLogService.recordMutation("ADMIN_USER_PASSWORD_RESET", "User", saved.getId(), null,
            Map.of("mustChangePassword", wasRequiredToChangePassword), Map.of("mustChangePassword", true));

        String changeTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(Instant.now());
        emailService.sendAccountPasswordReset(
            saved.getId(),
            saved.getEmail(),
            saved.getFullName(),
            changeTime,
            temporaryPassword,
            null
        );

        return response;
    }

    private User requiredUser(UUID id) {
        return userRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("User was not found"));
    }

    private void assertEditableTarget(User user) {
        if (user.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Administrator accounts are protected and cannot be modified");
        }
    }

    private Set<RoleCode> normalizeRoles(Set<RoleCode> roles) {
        if (roles == null || roles.isEmpty() || roles.contains(null)) {
            throw ApiExceptions.validation("At least one valid role is required", null);
        }
        return Set.copyOf(roles);
    }

    private Set<RoleCode> validateRoleAssignment(ActorPrincipal actor, User target, Set<RoleCode> requestedRoles) {
        Set<RoleCode> roleCodes = normalizeRoles(requestedRoles);
        if (actor.userId().equals(target.getId())
            && target.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.ADMIN)
            && !roleCodes.contains(RoleCode.ADMIN)
            && userRepository.countByRoles_Code(RoleCode.ADMIN) <= 1) {
            throw ApiExceptions.conflict("The last administrator cannot remove their own ADMIN role");
        }
        return roleCodes;
    }

    private void assertNotCustomerSelfMutation(ActorPrincipal actor, User target) {
        if (actor.userId().equals(target.getId())
            && target.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Customer accounts cannot assign themselves a role or facility");
        }
    }

    private void validateFacilityAssignment(Set<RoleCode> targetRoles, Map<UUID, FacilityScopeLevel> scopes) {
        boolean hasFacilityRole = targetRoles.contains(RoleCode.STAFF) || targetRoles.contains(RoleCode.MANAGER);
        boolean hasCustomerRole = targetRoles.contains(RoleCode.CUSTOMER);
        if (!scopes.isEmpty() && (!hasFacilityRole || hasCustomerRole)) {
            throw ApiExceptions.validation("Only Staff or Manager accounts can have facility scopes", null);
        }
    }

    private Set<RoleCode> roleCodes(User user) {
        return user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
    }

    private Map<UUID, FacilityScopeLevel> currentScopeMap(User user) {
        return scopeRepository.findByUserId(user.getId()).stream()
            .collect(Collectors.toMap(scope -> scope.getFacility().getId(), scope -> scope.getScopeLevel()));
    }

    private Set<Role> resolveRoles(Set<RoleCode> roleCodes) {
        Set<Role> roles = new HashSet<>();
        for (RoleCode code : roleCodes) {
            roles.add(roleRepository.findByCode(code)
                .orElseThrow(() -> ApiExceptions.conflict("The requested role is not initialized: " + code)));
        }
        return roles;
    }

    private Map<UUID, FacilityScopeLevel> normalizeScopes(Map<UUID, FacilityScopeLevel> rawScopes) {
        if (rawScopes == null) {
            return Map.of();
        }
        if (rawScopes.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw ApiExceptions.validation("facilityScopes must contain valid facility ids and scope levels", null);
        }
        List<Facility> facilities = facilityRepository.findAllById(rawScopes.keySet());
        if (facilities.size() != rawScopes.size()) {
            Set<UUID> found = facilities.stream().map(Facility::getId).collect(Collectors.toSet());
            Set<UUID> missing = rawScopes.keySet().stream().filter(id -> !found.contains(id)).collect(Collectors.toSet());
            throw ApiExceptions.validation("One or more facilities do not exist", missing);
        }
        return Map.copyOf(rawScopes);
    }

    private void replaceScopes(User user, Map<UUID, FacilityScopeLevel> scopes) {
        scopeRepository.deleteByUserId(user.getId());
        Map<UUID, Facility> facilities = facilityRepository.findAllById(scopes.keySet()).stream()
            .collect(Collectors.toMap(Facility::getId, facility -> facility));
        List<UserFacilityScope> entities = scopes.entrySet().stream().map(entry -> {
            UserFacilityScope scope = new UserFacilityScope();
            scope.setUser(user);
            scope.setFacility(facilities.get(entry.getKey()));
            scope.setScopeLevel(entry.getValue());
            return scope;
        }).toList();
        scopeRepository.saveAllAndFlush(entities);
    }

    private void revokeSessions(User user) {
        sessionRepository.revokeActiveByUserId(user.getId(), Instant.now());
    }

    private AdminUserResponse toResponse(User user) {
        Map<UUID, FacilityScopeLevel> scopes = currentScopeMap(user);
        return new AdminUserResponse(
            user.getId(),
            user.getFullName(),
            user.getEmail(),
            user.getPhone(),
            user.getStatus(),
            user.getRoles().stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet()),
            Map.copyOf(scopes),
            user.isMustChangePassword(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiExceptions.validation(field + " must not be blank", null);
        }
        return value.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
