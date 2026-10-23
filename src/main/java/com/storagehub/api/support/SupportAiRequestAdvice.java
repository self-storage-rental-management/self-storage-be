package com.storagehub.api.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiError;
import com.storagehub.common.api.ApiErrorResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@RestControllerAdvice(assignableTypes = CustomerAiChatController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SupportAiRequestAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper mapper;
    public SupportAiRequestAdvice(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.getParameterType() == SupportAiReplyRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
                                          Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = input.getBody().readNBytes(65_537);
        if (bytes.length > 65_536) throw ApiExceptions.validation("AI request body exceeds 64 KiB", null);
        JsonNode root;
        try (var parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = mapper.readTree(parser);
            if (parser.nextToken() != null) throw new IOException("Trailing JSON");
        } catch (IOException exception) {
            throw ApiExceptions.validation("Invalid AI request JSON", null);
        }
        fields(root, Set.of("message", "history"));
        if (root.hasNonNull("history")) {
            var history = root.get("history");
            if (!history.isArray() || history.size() > 10) throw ApiExceptions.validation("Invalid AI history", null);
            for (var message : history) fields(message, Set.of("role", "content"));
        }
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw ApiExceptions.validation("AI body must be an object", null);
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw ApiExceptions.validation("Unsupported AI request field", null);
        });
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiErrorResponse> malformed() {
        // Jackson diagnostics may contain fragments of chat text: never return the raw cause.
        return ResponseEntity.badRequest().body(new ApiErrorResponse(new ApiError(
            ErrorCode.VALIDATION_ERROR.name(), "AI request body or required header is invalid", null,
            CorrelationIdContext.current())));
    }
}
