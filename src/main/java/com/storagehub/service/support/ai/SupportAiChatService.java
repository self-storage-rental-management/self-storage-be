package com.storagehub.service.support.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.support.SupportAiReplyRequest;
import com.storagehub.api.support.SupportAiReplyResponse;
import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.ErrorCode;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Stateless pilot: no ticket, notification, record lookup or database transaction around Gemini. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupportAiChatService {
    public static final String KNOWLEDGE_VERSION = SupportPublicKnowledge.VERSION;
    private static final long REPLAY_TTL_MS = 600_000;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final SupportAiProvider provider;
    private final SupportQuickFaq quickFaq;
    private final SupportPublicKnowledge publicKnowledge;
    private final Semaphore slots = new Semaphore(8);
    private final Map<RequestKey, Attempt> attempts = new HashMap<>();
    private final Map<UUID, Window> windows = new HashMap<>();

    private record RequestKey(UUID customerId, UUID requestId) {}
    private record Attempt(String fingerprint, long createdAt, CompletableFuture<SupportAiReplyResponse> result) {}
    private record Window(long start, int count) {}

    public SupportAiReplyResponse reply(ActorPrincipal actor, SupportAiReplyRequest body, UUID requestId) {
        authorize(actor);
        var messages = messages(body);
        var quickAnswer = quickFaq.answer(body.message());
        if (quickAnswer.isEmpty()) provider.requireAvailable();
        String fingerprint = fingerprint(messages);
        var key = new RequestKey(actor.userId(), requestId);
        Attempt attempt;
        boolean owner = false;
        synchronized (attempts) {
            long now = System.currentTimeMillis();
            attempts.entrySet().removeIf(entry -> entry.getValue().result().isDone()
                && now - entry.getValue().createdAt() >= REPLAY_TTL_MS);
            windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= 60_000);
            attempt = attempts.get(key);
            if (attempt != null && !attempt.fingerprint().equals(fingerprint)) {
                throw ApiExceptions.conflict("Idempotency-Key đã được dùng với nội dung khác.");
            }
            if (attempt == null) {
                var window = windows.get(actor.userId());
                if (attempts.size() >= 2000 || windows.size() >= 2000
                    || (window != null && window.count() >= 10)) throw rateLimit();
                if (quickAnswer.isEmpty() && !slots.tryAcquire()) throw rateLimit();
                windows.put(actor.userId(), new Window(window == null ? now : window.start(), window == null ? 1 : window.count() + 1));
                attempt = new Attempt(fingerprint, now, new CompletableFuture<>());
                attempts.put(key, attempt);
                owner = true;
            }
        }
        if (owner) {
            long started = System.nanoTime();
            try {
                var answer = quickAnswer.isPresent()
                    ? new SupportAiProvider.Answer(quickAnswer.get(), "public-guidance-v2", false)
                    : provider.answer(messages);
                String source = quickAnswer.isPresent() ? "FAQ" : "GEMINI";
                attempt.result().complete(new SupportAiReplyResponse(requestId, "AI", source, publicKnowledge.approved(answer.text()), answer.model(),
                    KNOWLEDGE_VERSION, Instant.now(), answer.truncated()));
                log.info("Support assistant reply: source={}, elapsedMs={}", source, (System.nanoTime() - started) / 1_000_000);
            } catch (ApiException exception) {
                attempt.result().completeExceptionally(exception);
            } catch (RuntimeException exception) {
                attempt.result().completeExceptionally(new ApiException(ErrorCode.AI_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE, "Trợ lý AI chưa sẵn sàng. Bạn có thể gặp nhân viên."));
            } finally {
                if (quickAnswer.isEmpty()) slots.release();
            }
        }
        try {
            // A concurrent replay waits for the original result; never starts a second provider call.
            return attempt.result().get(31, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ApiException apiException) throw apiException;
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Trợ lý AI chưa sẵn sàng.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiExceptions.conflict("Yêu cầu đang được xử lý. Giữ nguyên Idempotency-Key khi kiểm tra lại.");
        } catch (TimeoutException exception) {
            throw ApiExceptions.conflict("Yêu cầu đang được xử lý. Giữ nguyên Idempotency-Key khi kiểm tra lại.");
        }
    }

    private void authorize(ActorPrincipal actor) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) throw ApiExceptions.forbidden("Chỉ Customer được sử dụng trợ lý này.");
        // Query the current database role/status, rather than relying on potentially stale JWT claims.
        long count = em.createQuery("select count(u) from User u join u.roles r where u.id=:id and u.status=:active and r.code=:role", Long.class)
            .setParameter("id", actor.userId()).setParameter("active", UserStatus.ACTIVE)
            .setParameter("role", RoleCode.CUSTOMER).getSingleResult();
        if (count == 0) throw ApiExceptions.forbidden("Tài khoản Customer không còn hoạt động hoặc không còn quyền.");
    }

    private List<SupportAiProvider.Message> messages(SupportAiReplyRequest body) {
        var result = new ArrayList<SupportAiProvider.Message>();
        int total = body.message().length();
        if (body.history() != null) {
            for (var message : body.history()) {
                total += message.content().length();
                result.add(new SupportAiProvider.Message(message.role().name(), message.content().trim()));
            }
        }
        if (total > 8000) throw ApiExceptions.validation("Tổng nội dung và lịch sử không được vượt quá 8000 ký tự.", null);
        result.add(new SupportAiProvider.Message("user", body.message().trim()));
        return List.copyOf(result);
    }

    private String fingerprint(List<SupportAiProvider.Message> messages) {
        try {
            byte[] data = mapper.writeValueAsString(messages).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot fingerprint support AI request");
        }
    }

    private static ApiException rateLimit() {
        return new ApiException(ErrorCode.AI_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
            "Đã đạt giới hạn lượt hỏi hoặc trợ lý đang bận. Vui lòng chờ hoặc gặp nhân viên.");
    }
}
