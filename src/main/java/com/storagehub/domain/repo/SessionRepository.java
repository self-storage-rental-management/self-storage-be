package com.storagehub.domain.repo;

import com.storagehub.domain.model.Session;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    @EntityGraph(attributePaths = "user")
    @Query("""
        select session from Session session
        where (:userId is null or session.user.id = :userId)
        """)
    Page<Session> search(@Param("userId") UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Optional<Session> findByIdAndRevokedAtIsNull(UUID id);

    @EntityGraph(attributePaths = "user")
    Optional<Session> findByRefreshTokenHashAndRevokedAtIsNull(String refreshTokenHash);

    @Modifying
    @Query("update Session session set session.revokedAt = :revokedAt where session.user.id = :userId and session.revokedAt is null")
    int revokeActiveByUserId(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}
