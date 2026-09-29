package com.storagehub.domain.repo;

import com.storagehub.domain.model.LoginHistory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, UUID> {

    long countByEmailAttemptedAndSuccessFalseAndOccurredAtAfter(String emailAttempted, Instant occurredAt);

    @EntityGraph(attributePaths = "user")
    List<LoginHistory> findTop5BySuccessFalseOrderByOccurredAtDesc();

    @EntityGraph(attributePaths = "user")
    @Query("""
        select history from LoginHistory history
        left join history.user user
        where (:success is null or history.success = :success)
          and (:userId is null or user.id = :userId)
          and (:search is null
              or lower(history.emailAttempted) like lower(concat('%', :search, '%'))
              or lower(coalesce(user.fullName, '')) like lower(concat('%', :search, '%')))
        """)
    Page<LoginHistory> search(
        @Param("success") Boolean success,
        @Param("userId") UUID userId,
        @Param("search") String search,
        Pageable pageable
    );
}
