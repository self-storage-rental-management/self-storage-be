package com.storagehub.service.support.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ErrorCode;
import com.storagehub.config.SupportAiProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class GeminiSupportAiProvider implements SupportAiProvider {
    private static final String GOOGLE_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai";
    private final SupportAiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String knowledge;
    private final SupportPublicKnowledge publicKnowledge;

    public GeminiSupportAiProvider(SupportAiProperties properties, ObjectMapper mapper,SupportPublicKnowledge publicKnowledge) throws IOException {
        this.properties = properties;
        this.mapper = mapper;
        this.publicKnowledge=publicKnowledge;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        this.knowledge = new ClassPathResource("ai/support-faq-v1.txt").getContentAsString(StandardCharsets.UTF_8)
            + "\nPUBLIC_CATALOG_JSON:\n" + publicKnowledge.selectionContext(mapper);
    }

    @Override
    public void requireAvailable() {
        if (!properties.isEnabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, HttpStatus.SERVICE_UNAVAILABLE,
                "Trợ lý AI chưa được bật. Bạn có thể chọn gặp nhân viên.");
        }
        String base = properties.getBaseUrl();
        // Never send this credential to a different host or follow a redirect.
        if (!"gemini".equals(properties.getProvider()) || properties.getApiKey() == null
            || properties.getApiKey().isBlank() || base == null
            || !GOOGLE_ENDPOINT.equals(base.replaceAll("/+$", ""))
            || properties.getModel() == null || !properties.getModel().matches("gemini-[A-Za-z0-9._-]{1,100}")) {
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                "Trợ lý AI chưa sẵn sàng. Bạn có thể chọn gặp nhân viên.");
        }
    }

    @Override
    public Answer answer(List<Message> messages) {
        requireAvailable();
        var input = new ArrayList<Message>();
        input.add(new Message("system", knowledge));
        // Do not treat forged client history as facts or instructions.
        if(messages.isEmpty())return new Answer(SupportPublicKnowledge.UNKNOWN,properties.getModel(),false);
        input.add(new Message("user",messages.getLast().content()));
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of(
                "model", properties.getModel(), "messages", input,
                "temperature", 0, "max_tokens", 128,
                "stream", false, "n", 1));
            var request = HttpRequest.newBuilder(URI.create(GOOGLE_ENDPOINT + "/chat/completions"))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            // Both headers and body must finish before the total deadline. No automatic retries.
            pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
            var response = pending.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Support AI provider rejected request: status={}, model={}", response.statusCode(), properties.getModel());
            }
            if (response.statusCode() == 429) {
                throw new ApiException(ErrorCode.AI_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                    "Dịch vụ AI đang giới hạn lượt hỏi. Vui lòng chờ hoặc gặp nhân viên.");
            }
            if (response.statusCode() == 503) {
                throw new ApiException(ErrorCode.AI_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Dịch vụ Gemini đang bận. Vui lòng thử lại sau hoặc chọn tab Nhân viên.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw providerError();
            var choice = mapper.readTree(response.body()).path("choices").path(0);
            String finish = choice.path("finish_reason").asText("");
            var content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()
                || !(finish.equals("stop") || finish.equals("length"))) throw providerError();
            // Never return generated prose, links, amounts or instructions to a customer.
            String text=finish.equals("stop")?publicKnowledge.grounded(content.asText(),mapper):SupportPublicKnowledge.UNKNOWN;
            return new Answer(text, properties.getModel(), false);
        } catch (TimeoutException exception) {
            throw timeoutError();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw timeoutError();
        } catch (ExecutionException exception) {
            log.warn("Support AI transport failed: type={}", exception.getCause() == null
                ? "unknown" : exception.getCause().getClass().getSimpleName());
            if (exception.getCause() instanceof HttpTimeoutException) throw timeoutError();
            throw providerError();
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("Support AI request/response failed: type={}", exception.getClass().getSimpleName());
            throw providerError();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private static ApiException timeoutError() {
        return new ApiException(ErrorCode.AI_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT,
            "Trợ lý AI phản hồi quá thời gian cho phép. Câu hỏi chưa có câu trả lời; bạn có thể gặp nhân viên.");
    }

    private static ApiException providerError() {
        // Do not expose provider response, request, key or raw exception to the client/log.
        return new ApiException(ErrorCode.AI_PROVIDER_ERROR, HttpStatus.BAD_GATEWAY,
            "Chưa nhận được câu trả lời từ trợ lý AI. Bạn có thể chọn gặp nhân viên.");
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("AI response exceeded limit"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
