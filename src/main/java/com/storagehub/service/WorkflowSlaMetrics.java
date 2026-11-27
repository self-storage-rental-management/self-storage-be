package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowSlaMetrics {
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    public record Observation(String workflow, UUID resource, UUID facility, RoleCode owner, Instant startedAt, Instant dueAt) {}
    public record Row(UUID facilityId, String facility, RoleCode owner, long pending, long overdue,
        long closed, long onTime, BigDecimal averageSeconds) {}

    public void lockScan() {
        jdbc.queryForObject("SELECT id FROM workflow_sla_metric_runs WHERE id=1 FOR UPDATE", Integer.class);
    }

    public void observe(List<Observation> active, Instant now) {
        String run = UUID.randomUUID().toString();
        if (!active.isEmpty()) jdbc.batchUpdate("""
            INSERT INTO workflow_sla_metrics
                (workflow_type,resource_id,facility_id,owner_role,started_at,due_at,last_seen_run)
            VALUES (?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE due_at=VALUES(due_at),last_seen_run=VALUES(last_seen_run),closed_at=NULL
            """, active.stream().map(item -> new Object[] {item.workflow(), item.resource().toString(),
                item.facility().toString(), item.owner().name(), timestamp(item.startedAt()), timestamp(item.dueAt()), run}).toList());
        jdbc.update("UPDATE workflow_sla_metrics SET closed_at=? WHERE closed_at IS NULL AND last_seen_run<>?",
            timestamp(now), run);
    }

    @Transactional(readOnly = true)
    public List<Row> summary(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Chỉ tài khoản nội bộ được xem KPI xử lý.");
        Instant now = Instant.now();
        var data = jdbc.query("""
            SELECT facility_id,owner_role,
              SUM(CASE WHEN closed_at IS NULL THEN 1 ELSE 0 END) pending_count,
              SUM(CASE WHEN closed_at IS NULL AND due_at<=? THEN 1 ELSE 0 END) overdue_count,
              COUNT(closed_at) closed_count,
              SUM(CASE WHEN closed_at<=due_at THEN 1 ELSE 0 END) on_time_count,
              AVG(CASE WHEN closed_at IS NOT NULL THEN GREATEST(0,TIMESTAMPDIFF(SECOND,started_at,closed_at)) END) average_seconds
            FROM workflow_sla_metrics WHERE closed_at IS NULL OR closed_at>=?
            GROUP BY facility_id,owner_role
            """, (rs, i) -> new Row(UUID.fromString(rs.getString("facility_id")), null, RoleCode.valueOf(rs.getString("owner_role")),
                rs.getLong("pending_count"), rs.getLong("overdue_count"), rs.getLong("closed_count"),
                rs.getLong("on_time_count"), rs.getBigDecimal("average_seconds")), timestamp(now), timestamp(now.minus(Duration.ofDays(30))));
        var visible = new ArrayList<Row>();
        for (Row row : data) {
            var scope = actor.facilityScopes().get(row.facilityId());
            if (!actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.ADMIN) && (scope == null || !scope.includes(FacilityScopeLevel.OPERATE))) continue;
            var facility = em.find(Facility.class, row.facilityId());
            if (facility == null) continue;
            visible.add(new Row(row.facilityId(), facility.getName(), row.owner(), row.pending(), row.overdue(),
                row.closed(), row.onTime(), row.averageSeconds()));
        }
        visible.sort(Comparator.comparing(Row::facility).thenComparing(Row::owner));
        return visible;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
    }
}
