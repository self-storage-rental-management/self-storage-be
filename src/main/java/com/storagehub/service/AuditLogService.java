package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.domain.model.ActivityLog;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ActivityLogRepository;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final ActivityLogRepository activityLogRepository;
    private final FacilityRepository facilityRepository;
    private final UserRepository userRepository;
    private final ActorContext actorContext;
    private final ObjectMapper objectMapper;

    @Transactional
    public ActivityLog recordMutation(
        String action,
        String entityType,
        UUID entityId,
        UUID facilityId,
        Object beforeState,
        Object afterState
    ) {
        User actor = null;
        try {
            ActorPrincipal principal = actorContext.required();
            actor = userRepository.getReferenceById(principal.userId());
        } catch (RuntimeException ignored) {
            // Verified system webhooks have no user actor; the correlation id still identifies the mutation.
        }
        return recordMutation(actor, action, entityType, entityId, facilityId, beforeState, afterState);
    }

    @Transactional
    public ActivityLog recordMutation(
        User actor,
        String action,
        String entityType,
        UUID entityId,
        UUID facilityId,
        Object beforeState,
        Object afterState
    ) {
        ActivityLog log = new ActivityLog();
        log.setActor(actor);
        if (facilityId != null) {
            Facility facility = facilityRepository.getReferenceById(facilityId);
            log.setFacility(facility);
        }
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setBeforeStateJson(toJson(beforeState));
        log.setAfterStateJson(toJson(afterState));
        log.setCorrelationId(CorrelationIdContext.current());
        return activityLogRepository.saveAndFlush(log);
    }

    private String toJson(Object state) {
        if (state == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit state", exception);
        }
    }

}
