package com.storagehub.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class DisconnectedResponseTests {
    @RestController
    static class DisconnectedStream {
        @GetMapping("/disconnected-stream")
        void stream(HttpServletResponse response) throws AsyncRequestNotUsableException {
            response.setContentType("text/event-stream");
            throw new AsyncRequestNotUsableException("Client disconnected");
        }
    }

    @Test
    void handlesDisconnectedStreamWithoutWritingJsonIntoTheResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DisconnectedStream())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        var result = mvc.perform(get("/disconnected-stream"))
            .andExpect(content().string("")).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
    }
}
