package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.domain.model.ProcessingLease;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A staff member opened a record, signed in again (new session), and was then told
 * "Hồ sơ đang được <their own name> xử lý. Bạn chỉ có thể xem" — nobody else had taken the record.
 * Ownership must follow the person, not the browser session.
 */
class ProcessingLeaseOwnershipTests {

    private final ProcessingLeaseService service = new ProcessingLeaseService(
        null, null, null, null, null, null, null, null, null, null);

    private ProcessingLease heldBy(UUID userId, UUID sessionId) {
        ProcessingLease lease = new ProcessingLease();
        lease.setOwnerId(userId);
        lease.setSessionId(sessionId);
        lease.setToken(UUID.randomUUID());
        lease.setExpiresAt(Instant.now().plusSeconds(120));
        return lease;
    }

    private boolean newerSession(com.storagehub.security.ActorPrincipal actor, ProcessingLease lease) {
        return (boolean) ReflectionTestUtils.invokeMethod(service, "sameUserNewerSession", actor, lease);
    }

    private static com.storagehub.security.ActorPrincipal actor(UUID userId, UUID sessionId) {
        return new com.storagehub.security.ActorPrincipal(
            userId, sessionId, java.util.Set.of(), java.util.Set.of(), java.util.Map.of());
    }

    @Test
    void sameUserInANewSessionMayTakeTheLeaseBack() {
        UUID userId = UUID.randomUUID();
        UUID oldSession = UUID.randomUUID();
        UUID newSession = UUID.randomUUID();
        ProcessingLease lease = heldBy(userId, oldSession);

        assertThat(newerSession(actor(userId, newSession), lease)).isTrue();
    }

    @Test
    void theSameSessionStillOwnsItsLease() {
        UUID userId = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        ProcessingLease lease = heldBy(userId, session);

        // Not "a newer session": this session already owns it and keeps it.
        assertThat(newerSession(actor(userId, session), lease)).isFalse();
    }

    @Test
    void anotherUserNeverTakesTheLease() {
        ProcessingLease lease = heldBy(UUID.randomUUID(), UUID.randomUUID());

        assertThat(newerSession(actor(UUID.randomUUID(), UUID.randomUUID()), lease)).isFalse();
    }

    @Test
    void anExpiredLeaseIsNotTakenOverAsAnewerSession() {
        UUID userId = UUID.randomUUID();
        ProcessingLease lease = heldBy(userId, UUID.randomUUID());
        lease.setExpiresAt(Instant.now().minusSeconds(5));

        assertThat(newerSession(actor(userId, UUID.randomUUID()), lease)).isFalse();
    }
}
