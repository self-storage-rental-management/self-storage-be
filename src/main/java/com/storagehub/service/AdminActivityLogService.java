package com.storagehub.service;

import com.storagehub.api.admin.AdminActivityLogResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.ActivityLog;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.ActivityLogRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final AdminAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public PageResponse<AdminActivityLogResponse> list(
        ActorPrincipal actor,
        int page,
        int size,
        String search,
        String entityType,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_AUDIT_LOGS);
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }
        return PageResponse.from(
            activityLogRepository.search(clean(search), clean(entityType), PageRequest.of(
                page, size, Sort.by(Sort.Direction.DESC, "createdAt")
            )).map(this::toResponse),
            correlationId
        );
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AdminActivityLogResponse toResponse(ActivityLog log) {
        Set<RoleCode> roles = log.getActor() == null
            ? Set.of()
            : log.getActor().getRoles().stream()
                .map(role -> role.getCode()).collect(Collectors.toUnmodifiableSet());
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
