package com.storagehub.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Invalidates catalog views only after a manager mutation has committed. */
@Service
public class GoodsCatalogEvents {
    public record Changed() {}
    private final Map<UUID, SseEmitter> subscribers = new ConcurrentHashMap<>();
    private volatile String revision = UUID.randomUUID().toString();

    public SseEmitter subscribe() {
        UUID id = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        subscribers.put(id, emitter);
        emitter.onCompletion(() -> subscribers.remove(id));
        emitter.onTimeout(() -> { subscribers.remove(id); emitter.complete(); });
        emitter.onError(error -> subscribers.remove(id));
        send(id, emitter, "ready");
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(Changed event) {
        revision = UUID.randomUUID().toString();
        subscribers.forEach((id, emitter) -> send(id, emitter, "catalog-changed"));
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        subscribers.forEach((id, emitter) -> send(id, emitter, "heartbeat"));
    }

    private void send(UUID id, SseEmitter emitter, String event) {
        try {
            emitter.send(SseEmitter.event().name(event).data(Map.of("revision", revision)));
        } catch (IOException | IllegalStateException error) {
            subscribers.remove(id);
            emitter.complete();
        }
    }
}
