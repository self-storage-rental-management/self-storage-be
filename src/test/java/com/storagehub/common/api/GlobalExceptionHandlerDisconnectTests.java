package com.storagehub.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class GlobalExceptionHandlerDisconnectTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void wrappedWindowsClientAbortDoesNotAttemptAnotherResponse() {
        var response = mock(jakarta.servlet.http.HttpServletResponse.class);
        var abort = new ClientAbortException(new IOException(
            "An established connection was aborted by the software in your host machine"));
        var exception = new HttpMessageNotWritableException("Could not write JSON",
            new AsyncRequestNotUsableException("ServletOutputStream failed to write", abort));
        assertThat(handler.handleUnwritableResponse(exception, response)).isNull();
        verifyNoInteractions(response);
    }

    @Test
    void genuineSerializationFailureReturnsServerError() {
        var response = new MockHttpServletResponse();
        var exception = new HttpMessageNotWritableException("Could not write JSON",
            new IllegalStateException("Invalid JSON property imageUrl"));
        var result = handler.handleUnwritableResponse(exception, response);
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void unrelatedIoFailureIsNotClassifiedAsClientDisconnect() {
        var result = handler.handleUnwritableResponse(new HttpMessageNotWritableException(
            "Could not write JSON", new IOException("Failed to read image data")), new MockHttpServletResponse());
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void committedSerializationFailureDoesNotWriteAnotherBody() throws IOException {
        var response = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);
        assertThat(handler.handleUnwritableResponse(new HttpMessageNotWritableException(
            "Invalid JSON", new IllegalStateException("Broken serializer")), response)).isNull();
        verify(response, never()).resetBuffer();
        verify(response, never()).getOutputStream();
    }
}
