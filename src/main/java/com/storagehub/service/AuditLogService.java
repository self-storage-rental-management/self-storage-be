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

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordDenial(String action, String entityType, UUID entityId, UUID facilityId, Object reason) {
        recordMutation(action, entityType, entityId, facilityId, null, reason);
    }

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
            // Scheduled system mutations have no user actor; the correlation id still identifies the mutation.
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

    private void redactIdentity(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String key = name.toLowerCase(java.util.Locale.ROOT);
                var value = object.get(name);
                if (key.contains("identitydocument") || key.contains("identityhash") || key.equals("cccd")) object.put(name, "[REDACTED]");
                else if (value.isTextual()) object.put(name, value.asText().replaceAll("(?<![0-9])(?:[0-9][\\s.\\-]*){11}[0-9](?![0-9])", "[CCCD đã ẩn]"));
                else redactIdentity(value);
            }
        } else if (node.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                var value = array.get(i);
                if (value.isTextual()) array.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(value.asText().replaceAll("(?<![0-9])(?:[0-9][\\s.\\-]*){11}[0-9](?![0-9])", "[CCCD đã ẩn]")));
                else redactIdentity(value);
            }
        }
    }

    private String toJson(Object state) {
        if (state == null) {
            return null;
        }
        try {
            var tree = objectMapper.valueToTree(state);
            redactIdentity(tree);
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit state", exception);
        }
    }

}
