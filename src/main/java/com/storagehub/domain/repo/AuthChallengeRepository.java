package com.storagehub.domain.repo;

import com.storagehub.domain.model.AuthChallenge;
import com.storagehub.domain.model.AuthChallengePurpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthChallengeRepository extends JpaRepository<AuthChallenge, UUID> {

    Optional<AuthChallenge> findTopByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
        UUID userId,
        AuthChallengePurpose purpose
    );

    Optional<AuthChallenge> findByTokenHashAndPurposeAndConsumedAtIsNull(
        String tokenHash,
        AuthChallengePurpose purpose
    );

    @Modifying
    @Query("""
        update AuthChallenge challenge
        set challenge.consumedAt = :consumedAt
        where challenge.user.id = :userId
          and challenge.purpose = :purpose
          and challenge.consumedAt is null
        """)
    int consumeActiveByUserAndPurpose(
        @Param("userId") UUID userId,
        @Param("purpose") AuthChallengePurpose purpose,
        @Param("consumedAt") Instant consumedAt
    );
}
