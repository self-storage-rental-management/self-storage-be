package com.storagehub.service;

import com.storagehub.api.admin.AdminActivityLogResponse;
import com.storagehub.api.admin.AdminDashboardResponse;
import com.storagehub.api.admin.AdminLoginHistoryResponse;
import com.storagehub.domain.model.ActivityLog;
import com.storagehub.domain.model.LoginHistory;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.ActivityLogRepository;
import com.storagehub.domain.repo.LoginHistoryRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final Set<String> SECURITY_ACTIONS = Set.of(
        "USER_REGISTERED",
        "EMAIL_VERIFIED",
        "PASSWORD_RESET",
        "PASSWORD_CHANGED",
        "USER_PROFILE_UPDATED",
        "SESSION_CREATED",
        "SESSION_REFRESHED",
        "SESSION_REVOKED",
        "ADMIN_USER_CREATED",
        "ADMIN_USER_UPDATED",
        "ADMIN_USER_ROLES_UPDATED",
        "ADMIN_USER_FACILITY_SCOPES_UPDATED",
        "ADMIN_USER_STATUS_CHANGED",
        "ADMIN_USER_PASSWORD_RESET",
        "UNLOCK_ACCOUNT",
        "ADMIN_SESSION_REVOKED",
        "ADMIN_ROLE_PERMISSIONS_UPDATED"
    );

    private final UserRepository userRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final ActivityLogRepository activityLogRepository;
    private final AdminAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard(ActorPrincipal actor) {
        authorizationService.require(actor, SystemPermission.VIEW_DASHBOARD);

        Map<RoleCode, Long> usersByRole = new EnumMap<>(RoleCode.class);
        Arrays.stream(RoleCode.values()).forEach(role -> usersByRole.put(role, 0L));
        userRepository.countUsersByRole().forEach(row -> usersByRole.put(row.getRoleCode(), row.getTotal()));

        PageRequest recentEvents = PageRequest.of(
            0,
            5,
            Sort.by(Sort.Direction.DESC, "createdAt")
        );
        List<AdminLoginHistoryResponse> recentLoginFailures = loginHistoryRepository
            .findTop5BySuccessFalseOrderByOccurredAtDesc()
            .stream()
            .map(this::toLoginHistoryResponse)
            .toList();
        List<AdminActivityLogResponse> recentSecurityEvents = activityLogRepository
            .findByActionIn(SECURITY_ACTIONS, recentEvents)
            .stream()
            .map(this::toActivityLogResponse)
            .toList();

        return new AdminDashboardResponse(
            userRepository.count(),
            Map.copyOf(usersByRole),
            userRepository.countByStatus(UserStatus.LOCKED),
            userRepository.countByStatus(UserStatus.DISABLED),
            recentLoginFailures,
            recentSecurityEvents
        );
    }

    private AdminLoginHistoryResponse toLoginHistoryResponse(LoginHistory history) {
        return new AdminLoginHistoryResponse(
            history.getId(),
            history.getUser() == null ? null : history.getUser().getId(),
            history.getUser() == null ? null : history.getUser().getFullName(),
            history.getEmailAttempted(),
            history.getUser() == null ? Set.of() : history.getUser().getRoles().stream()
                .map(Role::getCode)
                .collect(Collectors.toUnmodifiableSet()),
            history.isSuccess(),
            history.getIpAddress(),
            history.getUserAgent(),
            history.getFailureReason(),
            history.getOccurredAt()
        );
    }

    private AdminActivityLogResponse toActivityLogResponse(ActivityLog log) {
        Set<RoleCode> roles = log.getActor() == null
            ? Set.of()
            : log.getActor().getRoles().stream()
                .map(Role::getCode)
                .collect(Collectors.toUnmodifiableSet());
        return new AdminActivityLogResponse(
            log.getId(),
            log.getActor() == null ? null : log.getActor().getId(),
            log.getActor() == null ? "System" : log.getActor().getFullName(),
            log.getActor() == null ? null : log.getActor().getEmail(),
            roles,
            log.getFacility() == null ? null : log.getFacility().getId(),
            log.getAction(),
            log.getEntityType(),
            log.getEntityId(),
            log.getBeforeStateJson(),
            log.getAfterStateJson(),
            log.getCorrelationId(),
            log.getCreatedAt()
        );
    }
}
