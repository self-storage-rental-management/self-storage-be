package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.configuration.FacilityContentPolicy;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class FacilityContentPolicyService {
    private static final String KEY = "facility-content-policies";
    private final FacilityPolicyCatalogRepository catalogs;
    private final FacilityRepository facilities;
    private final AdminAuthorizationService authorization;
    private final AuditLogService audit;
    private final ObjectMapper mapper;
    public record Catalog(long version, List<FacilityContentPolicy> policies) {}
    private void requireBusiness(ActorPrincipal actor, boolean write) {
        if (!actor.hasRole(RoleCode.BUSINESS)) throw ApiExceptions.forbidden("Chỉ BO được quản lý chính sách cơ sở.");
        authorization.require(actor, write ? SystemPermission.MANAGE_POLICIES : SystemPermission.VIEW_POLICIES);
    }
    private Catalog read() {
        return catalogs.findById(KEY).map(entity -> {
            try { return new Catalog(entity.getVersion(), mapper.readValue(entity.getContentJson(), new TypeReference<List<FacilityContentPolicy>>() {})); }
            catch (Exception e) { throw new IllegalStateException("Cannot read facility policies", e); }
        }).orElseGet(() -> new Catalog(-1, List.of()));
    }
    @Transactional(readOnly=true)
    public Catalog list(ActorPrincipal actor) { requireBusiness(actor, false); return read(); }
    @Transactional
    public Catalog save(ActorPrincipal actor, long expectedVersion, List<FacilityContentPolicy> policies) {
        requireBusiness(actor, true);
        Catalog before = read();
        if (before.version() != expectedVersion) throw ApiExceptions.conflict("Chính sách đã được cập nhật ở nơi khác. Vui lòng tải lại trước khi lưu.");
        Set<String> ids = new HashSet<>();
        for (var policy : policies) {
            if (!ids.add(policy.id())) throw ApiExceptions.validation("Mã chính sách bị trùng.", null);
            if ("specific".equals(policy.scopeType()) && policy.facilityIds().isEmpty()) throw ApiExceptions.validation("Vui lòng chọn cơ sở áp dụng chính sách " + policy.name() + ".", null);
            if ("all".equals(policy.scopeType()) && !policy.facilityIds().isEmpty()) throw ApiExceptions.validation("Chính sách toàn hệ thống không được chứa danh sách cơ sở riêng.", null);
            for (UUID id : policy.facilityIds()) if (!facilities.existsById(id)) throw ApiExceptions.validation("Cơ sở áp dụng chính sách không tồn tại.", null);
        }
        FacilityPolicyCatalog entity = catalogs.findById(KEY).orElseGet(() -> { var item = new FacilityPolicyCatalog(); item.setId(KEY); return item; });
        try { entity.setContentJson(mapper.writeValueAsString(policies)); }
        catch (Exception e) { throw new IllegalStateException("Cannot save facility policies", e); }
        catalogs.saveAndFlush(entity);
        Catalog after = new Catalog(entity.getVersion(), policies);
        audit.recordMutation("FACILITY_CONTENT_POLICIES_UPDATED", "FacilityPolicyCatalog", UUID.nameUUIDFromBytes(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null, before, after);
        return after;
    }
    @Transactional(readOnly=true)
    public List<FacilityContentPolicy> customerPolicies(UUID facilityId) {
        var facility = facilities.findById(facilityId).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy cơ sở."));
        if (facility.getStatus() != FacilityStatus.active) return List.of();
        return read().policies().stream().filter(policy -> "all".equals(policy.scopeType()) || policy.facilityIds().contains(facilityId)).toList();
    }
}
