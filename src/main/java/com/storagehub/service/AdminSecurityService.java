package com.storagehub.service;

import com.storagehub.api.admin.AdminLoginHistoryResponse;
import com.storagehub.api.admin.AdminSessionResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.LoginHistory;
import com.storagehub.domain.model.Session;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.LoginHistoryRepository;
import com.storagehub.domain.repo.SessionRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSecurityService {

    private final LoginHistoryRepository loginHistoryRepository;
    private final SessionRepository sessionRepository;
    private final AdminAuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<AdminLoginHistoryResponse> loginHistory(
        ActorPrincipal actor,
        int page,
        int size,
        String search,
        Boolean success,
        UUID userId,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_AUDIT_LOGS);
        Page<LoginHistory> result = loginHistoryRepository.search(
            success, userId, clean(search), pageable(page, size, "occurredAt")
        );
        return PageResponse.from(result.map(this::toLoginHistoryResponse), correlationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminSessionResponse> sessions(
        ActorPrincipal actor,
        int page,
        int size,
        UUID userId,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_AUDIT_LOGS);
        Page<Session> result = sessionRepository.search(userId, pageable(page, size, "createdAt"));
        return PageResponse.from(result.map(this::toSessionResponse), correlationId);
    }

    @Transactional
    public AdminSessionResponse revokeSession(ActorPrincipal actor, UUID sessionId) {
        authorizationService.require(actor, SystemPermission.MANAGE_USERS);
        Session session = sessionRepository.findByIdAndRevokedAtIsNull(sessionId)
            .orElseThrow(() -> ApiExceptions.notFound("Active session was not found"));
        Instant revokedAt = Instant.now();
        session.setRevokedAt(revokedAt);
        Session saved = sessionRepository.saveAndFlush(session);
        auditLogService.recordMutation(
            "ADMIN_SESSION_REVOKED", "Session", saved.getId(), null,
            java.util.Map.of("active", true), java.util.Map.of("active", false, "revokedAt", revokedAt)
        );
        return toSessionResponse(saved);
    }

    private PageRequest pageable(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort));
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AdminLoginHistoryResponse toLoginHistoryResponse(LoginHistory history) {
        return new AdminLoginHistoryResponse(
            history.getId(),
            history.getUser() == null ? null : history.getUser().getId(),
            history.getUser() == null ? null : history.getUser().getFullName(),
            history.getEmailAttempted(),
            history.getUser() == null ? java.util.Set.of() : history.getUser().getRoles().stream()
                .map(role -> role.getCode()).collect(java.util.stream.Collectors.toUnmodifiableSet()),
            history.isSuccess(),
            history.getIpAddress(),
            history.getUserAgent(),
            history.getFailureReason(),
            history.getOccurredAt()
        );
    }

    private AdminSessionResponse toSessionResponse(Session session) {
        Instant now = Instant.now();
        return new AdminSessionResponse(
            session.getId(),
            session.getUser().getId(),
            session.getUser().getFullName(),
            session.getUser().getEmail(),
            session.getCreatedIp(),
            session.getUserAgent(),
            session.getCreatedAt(),
            session.getLastSeenAt(),
            session.getExpiresAt(),
            session.getRevokedAt(),
            session.isActive(now)
        );
    }
}
