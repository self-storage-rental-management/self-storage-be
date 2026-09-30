package com.storagehub.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

public final class ApiErrorWriter {

    private ApiErrorWriter() {
    }

    public static void write(
        HttpServletResponse response,
        ObjectMapper objectMapper,
        int status,
        ErrorCode code,
        String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
            response.getOutputStream(),
            new ApiErrorResponse(new ApiError(code.name(), message, null, CorrelationIdContext.current()))
        );
    }
}
