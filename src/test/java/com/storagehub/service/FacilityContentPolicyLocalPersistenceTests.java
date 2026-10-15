package com.storagehub.service;

import com.storagehub.api.configuration.FacilityContentPolicy;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="storagehub.local-policy-smoke", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK, properties={
    "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none",
    "app.bootstrap-demo-data.enabled=false", "app.bootstrap-admin.enabled=false"
})
@Transactional
class FacilityContentPolicyLocalPersistenceTests {
    @Autowired FacilityContentPolicyService service;
    @Autowired EntityManager em;
    @Test void createEditDeleteSurvivesDatabaseReadbackAndRollsBackAfterTest() {
        var actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.BUSINESS),
            Set.of(SystemPermission.MANAGE_POLICIES.code(), SystemPermission.VIEW_POLICIES.code()), Map.of());
        var before = service.list(actor);
        String id = "test-policy-" + UUID.randomUUID();
        var policies = new ArrayList<>(before.policies());
        policies.add(new FacilityContentPolicy(id, "Kiểm thử lưu chính sách", "Nội dung ban đầu", "Toàn bộ cơ sở",
            true, "Dữ liệu chỉ tồn tại trong giao dịch kiểm thử, tự rollback.", "", "all", List.of(), "billing"));
        var created = service.save(actor, before.version(), policies);
        em.clear();
        assertThat(service.list(actor).policies()).anyMatch(p -> p.id().equals(id));
        policies.set(policies.size()-1, new FacilityContentPolicy(id, "Kiểm thử lưu chính sách", "Nội dung đã sửa", "Toàn bộ cơ sở",
            true, "", "", "all", List.of(), "billing"));
        var edited = service.save(actor, created.version(), policies);
        em.clear();
        assertThat(service.list(actor).policies()).anyMatch(p -> p.id().equals(id) && p.value().equals("Nội dung đã sửa"));
        service.save(actor, edited.version(), before.policies());
        em.clear();
        assertThat(service.list(actor).policies()).noneMatch(p -> p.id().equals(id));
    }
}
