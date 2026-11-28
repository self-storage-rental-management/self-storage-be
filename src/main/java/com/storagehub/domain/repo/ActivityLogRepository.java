package com.storagehub.domain.repo;

import com.storagehub.domain.model.ActivityLog;
import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {
    List<ActivityLog> findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(String entityType, UUID entityId, String action);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<ActivityLog> findFirstByEntityTypeAndEntityIdAndActionAndFacility_IdInOrderByCreatedAtDesc(
        String entityType, UUID entityId, String action, Collection<UUID> facilityIds);

    @EntityGraph(attributePaths = {"actor"})
    List<ActivityLog> findByEntityTypeAndActionInAndEntityIdIn(
        String entityType, Collection<String> actions, Collection<UUID> entityIds);

    @EntityGraph(attributePaths = {"actor"})
    List<ActivityLog> findByEntityTypeAndActionAndEntityIdIn(String entityType, String action, Collection<UUID> entityIds);

    @EntityGraph(attributePaths = {"actor", "facility"})
    @Query("""
        select log from ActivityLog log
        left join log.actor actor
        where log.action not in ('RENTAL_SUSPICION_JOURNAL_ADDED', 'RENTAL_SUSPICION_TRANSFERRED', 'RENTAL_SUSPICION_CLOSED')
          and (:search is null
            or lower(log.action) like lower(concat('%', :search, '%'))
            or lower(log.entityType) like lower(concat('%', :search, '%'))
            or lower(coalesce(actor.fullName, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(actor.email, '')) like lower(concat('%', :search, '%')))
          and (:entityType is null or log.entityType = :entityType)
          and (:actorId is null or actor.id = :actorId)
          and (:from is null or log.createdAt >= :from)
          and (:to is null or log.createdAt < :to)
        """)
    Page<ActivityLog> search(
        @Param("search") String search,
        @Param("entityType") String entityType,
        @Param("actorId") UUID actorId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"actor", "facility"})
    @Query("""
        select log from ActivityLog log
        left join log.actor actor
        where log.action not in ('RENTAL_SUSPICION_JOURNAL_ADDED', 'RENTAL_SUSPICION_TRANSFERRED', 'RENTAL_SUSPICION_CLOSED')
          and (:facilityId is null or log.facility.id = :facilityId)
          and (:search is null
            or lower(log.action) like lower(concat('%', :search, '%'))
            or lower(log.entityType) like lower(concat('%', :search, '%'))
            or lower(coalesce(actor.fullName, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(actor.email, '')) like lower(concat('%', :search, '%')))
          and (:entityType is null or log.entityType = :entityType)
        """)
    Page<ActivityLog> searchFacilityActivities(
        @Param("facilityId") UUID facilityId,
        @Param("search") String search,
        @Param("entityType") String entityType,
        Pageable pageable
    );

    @Query(value = """
        select l.* from activity_logs l
        where l.entity_type = :entityType and l.action = :action
          and l.facility_id = UUID_TO_BIN(:facilityId)
          and JSON_UNQUOTE(JSON_EXTRACT(l.after_state_json, '$.rentalId')) = :rentalId
        order by l.created_at desc, l.id desc
        """, nativeQuery = true)
    List<ActivityLog> findReportsForRental(@Param("entityType") String entityType, @Param("action") String action,
        @Param("facilityId") String facilityId, @Param("rentalId") String rentalId);

    @EntityGraph(attributePaths = {"actor", "facility"})
    Page<ActivityLog> findByEntityTypeAndActionAndFacility_IdIn(String entityType, String action,
        Collection<UUID> facilityIds, Pageable pageable);

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
