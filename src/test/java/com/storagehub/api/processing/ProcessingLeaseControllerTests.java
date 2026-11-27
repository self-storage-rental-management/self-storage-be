package com.storagehub.api.processing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storagehub.security.ActorContext;
import com.storagehub.service.ProcessingLeaseService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProcessingLeaseControllerTests {
    @Test void tokenOnlyRequestsRemainCompatibleWithOptionalReclaimFlag() throws Exception {
        var leases = mock(ProcessingLeaseService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ProcessingLeaseController(mock(ActorContext.class), leases)).build();
        var id = UUID.randomUUID();
        var token = UUID.randomUUID();
        for (String route : new String[]{"requests", "heartbeat", "release", "acquire"}) {
            mvc.perform(post("/api/processing-locks/ASSIGNMENT/" + id + "/" + route)
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());
        }
        verify(leases).requestAccess(isNull(), eq(ProcessingLeaseService.Target.ASSIGNMENT), eq(id), eq(token));
        verify(leases).acquire(isNull(), eq(ProcessingLeaseService.Target.ASSIGNMENT), eq(id), eq(token), eq(false));
    }
}
