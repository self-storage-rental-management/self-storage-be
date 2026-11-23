package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WorkflowSlaMetricsTests {
    @Test
    void observationDoesNotDuplicateTasksAndSummaryRespectsFacilityScope() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(db);
        try {
            jdbc.execute("""
                CREATE TABLE workflow_sla_metrics (
                  workflow_type VARCHAR(32), resource_id CHAR(36), facility_id CHAR(36), owner_role VARCHAR(16),
                  started_at TIMESTAMP(6), due_at TIMESTAMP(6), last_seen_run CHAR(36), closed_at TIMESTAMP(6),
                  PRIMARY KEY (workflow_type, resource_id, started_at)
                )
                """);
            var em = mock(EntityManager.class);
            var metrics = new WorkflowSlaMetrics(jdbc, em);
            UUID facilityId = UUID.randomUUID();
            UUID otherFacilityId = UUID.randomUUID();
            Facility facility = new Facility();
            facility.setName("Kho kiểm thử");
            when(em.find(Facility.class, facilityId)).thenReturn(facility);
            Instant start = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            var first = new WorkflowSlaMetrics.Observation("UNIT_ASSIGNMENT", UUID.randomUUID(), facilityId, RoleCode.MANAGER,
                start, start.plusSeconds(1800));
            var second = new WorkflowSlaMetrics.Observation("UNIT_ASSIGNMENT", UUID.randomUUID(), otherFacilityId, RoleCode.MANAGER,
                start, start.plusSeconds(1800));
            metrics.observe(List.of(first, second), start.plusSeconds(300));
            metrics.observe(List.of(first, second), start.plusSeconds(600));
            metrics.observe(List.of(second), start.plusSeconds(1200));
            var manager = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
                Set.of(), Map.of(facilityId, FacilityScopeLevel.MANAGE));
            assertThat(metrics.summary(manager)).singleElement().satisfies(result -> {
                assertThat(result.facilityId()).isEqualTo(facilityId);
                assertThat(result.closed()).isEqualTo(1);
                assertThat(result.onTime()).isEqualTo(1);
                assertThat(result.pending()).isZero();
                assertThat(result.averageSeconds()).isEqualByComparingTo("1200");
            });
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}
