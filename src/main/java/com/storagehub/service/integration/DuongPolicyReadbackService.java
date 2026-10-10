package com.storagehub.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.PublishedRenewalPolicy;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.communication.CommunicationPolicyService;
import com.storagehub.service.renewal.integration.RenewalPolicyPublicationService;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BO management projection only. Never used as an effective policy by operational consumers. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class DuongPolicyReadbackService {
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final DuongResourceAccess access;
    @Value("${storagehub.integration.communication.enabled:false}") private boolean communicationEnabled;

    public Object read(ActorPrincipal actor, UUID facility, String kind, Long revision) {
        access.require(actor, RoleCode.BUSINESS, facility, FacilityScopeLevel.READ, SystemPermission.VIEW_POLICIES);
        if (!Set.of("renewal", "communication").contains(kind) || revision != null && revision < 1)
            throw ApiExceptions.validation("Invalid policy readback query", null);
        if (kind.equals("communication") && !communicationEnabled) throw ApiExceptions.notFound("Communication integration is disabled");
        String key = kind.equals("renewal") ? RenewalPolicyPublicationService.key(facility) : "duongCommunicationPolicy:" + facility;
        var settings = em.createQuery("select s from SystemSetting s where s.settingKey=:key", SystemSetting.class)
            .setParameter("key", key).setMaxResults(2).getResultList();
        if (settings.isEmpty()) throw ApiExceptions.notFound("Policy has not been published");
        if (settings.size() != 1) throw ApiExceptions.conflict("Policy source is ambiguous");
        var setting = settings.getFirst();
        var current = tree(setting.getValue(), facility);
        if (revision == null || current.path("revision").asLong() == revision) return decode(current, kind);
        // Audit snapshots are written in the same transaction as publication. No new ledger/schema/writer.
        String action = kind.equals("renewal") ? "RENEWAL_POLICY_PUBLISHED" : "COMMUNICATION_POLICY_PUBLISHED";
        var logs = em.createQuery("select l from ActivityLog l where l.entityType='SystemSetting' and l.entityId=:id and l.facility.id=:facility and l.action=:action", ActivityLog.class)
            .setParameter("id", setting.getId()).setParameter("facility", facility).setParameter("action", action).getResultList();
        var matches = new ArrayList<JsonNode>();
        for (var log : logs) {
            var snapshot = tree(log.getAfterStateJson(), facility);
            if (snapshot.path("revision").asLong() != revision) continue;
            if (log.getActor() == null || !log.getActor().getId().toString().equals(snapshot.path("publishedBy").asText()))
                throw ApiExceptions.conflict("Policy publication evidence is incomplete");
            matches.add(snapshot);
        }
        if (matches.isEmpty()) throw ApiExceptions.notFound("Publication revision cannot be verified");
        if (matches.size() != 1) throw ApiExceptions.conflict("Policy publication evidence is ambiguous");
        return decode(matches.getFirst(), kind);
    }
    private JsonNode tree(String json, UUID facility) {
        try {
            var n = mapper.readTree(json);
            if (n == null || !n.isObject() || !facility.toString().equals(n.path("facilityId").asText()) || !n.path("revision").canConvertToLong() || n.path("revision").asLong() < 1
                || n.path("version").asText().isBlank() || n.path("publishedBy").asText().isBlank() || n.path("publishedAt").asText().isBlank())
                throw ApiExceptions.conflict("Policy metadata is incomplete");
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) { throw ApiExceptions.conflict("Policy snapshot is invalid"); }
    }
    private Object decode(JsonNode n, String kind) {
        try {
            if (kind.equals("renewal")) {
                for (String field : List.of("quoteTtlMinutes", "paymentWindowHours", "requestWindowDays"))
                    if (!n.hasNonNull(field)) throw ApiExceptions.conflict("Policy fields are incomplete");
                var p = mapper.treeToValue(n, PublishedRenewalPolicy.class);
                if (!"RENEWAL_POLICY_V1".equals(p.schema())) throw ApiExceptions.conflict("Policy schema is invalid");
                RenewalPolicyPublicationService.validate(new PublishedRenewalPolicy.Input(p.revision(), p.effectiveFrom(), p.effectiveTo(), p.quoteTtlMinutes(), p.paymentWindowHours(), p.requestWindowDays(), p.depositRate(), p.eligiblePackageIds(), p.signing(), p.term()));
                return p;
            }
            var p = mapper.treeToValue(n, CommunicationPolicyService.Policy.class);
            if (p.effectiveFrom() == null || p.effectiveTo() != null && p.effectiveTo().isBefore(p.effectiveFrom())
                || p.reminderCooldownMinutes() != null && (p.reminderCooldownMinutes() < 1 || p.reminderCooldownMinutes() > 525600)
                || p.supportReviewDays() != null && (p.supportReviewDays() < 7 || p.supportReviewDays() > 365)
                || (p.supportReviewDays() == null) != (p.customerMayClose() == null)) throw ApiExceptions.conflict("Policy fields are invalid");
            return p;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) { throw ApiExceptions.conflict("Policy snapshot is invalid"); }
    }
}
