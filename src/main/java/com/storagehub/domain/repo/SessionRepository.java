package com.storagehub.domain.repo;

import com.storagehub.domain.model.Session;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    @EntityGraph(attributePaths = "user")
    Optional<Session> findByIdAndRevokedAtIsNull(UUID id);
}
