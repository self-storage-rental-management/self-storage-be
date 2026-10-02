package com.storagehub.domain.repo;

import com.storagehub.domain.model.ActivityLog;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    @EntityGraph(attributePaths = {"actor", "facility"})
    @Query("""
        select log from ActivityLog log
        left join log.actor actor
        where (:search is null
            or lower(log.action) like lower(concat('%', :search, '%'))
            or lower(log.entityType) like lower(concat('%', :search, '%'))
            or lower(coalesce(actor.fullName, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(actor.email, '')) like lower(concat('%', :search, '%')))
          and (:entityType is null or log.entityType = :entityType)
        """)
    Page<ActivityLog> search(
        @Param("search") String search,
        @Param("entityType") String entityType,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"actor", "facility"})
    List<ActivityLog> findByActionIn(Collection<String> actions, Pageable pageable);

    @Query("""
        select count(log) > 0 from ActivityLog log
        where log.action = :action
          and log.entityId = :entityId
          and log.createdAt >= :since
        """)
    boolean existsByActionAndEntityIdAndCreatedAtAfter(
        @Param("action") String action,
        @Param("entityId") UUID entityId,
        @Param("since") java.time.Instant since
    );
}
