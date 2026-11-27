package com.storagehub.api.processing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessingLeaseTokenRequestTest {
    @Test
    void acceptsMissingOptionalReclaimFlagWithStrictPrimitiveHandling() throws Exception {
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        var id = UUID.randomUUID();
        var request = mapper.readValue("{\"token\":\"" + id + "\"}", ProcessingLeaseController.TokenRequest.class);
        assertThat(request.token()).isEqualTo(id);
        assertThat(Boolean.TRUE.equals(request.reclaimSameOwner())).isFalse();
        var explicit = mapper.readValue("{\"token\":\"" + id + "\",\"reclaimSameOwner\":true}", ProcessingLeaseController.TokenRequest.class);
        assertThat(explicit.reclaimSameOwner()).isTrue();
    }
}
