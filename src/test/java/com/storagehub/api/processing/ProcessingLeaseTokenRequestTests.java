package com.storagehub.api.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcessingLeaseTokenRequestTests {
    private final JsonMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    private final UUID token = UUID.randomUUID();

    @Test
    void legacyTokenOnlyRequestRemainsValid() throws Exception {
        var request = mapper.readValue("{\"token\":\"" + token + "\"}",
            ProcessingLeaseController.TokenRequest.class);
        assertThat(request.token()).isEqualTo(token);
        assertThat(Boolean.TRUE.equals(request.reclaimSameOwner())).isFalse();
    }

    @Test
    void explicitReclaimIsPreserved() throws Exception {
        var request = mapper.readValue("{\"token\":\"" + token + "\",\"reclaimSameOwner\":true}",
            ProcessingLeaseController.TokenRequest.class);
        assertThat(request.reclaimSameOwner()).isTrue();
    }

    @Test
    void nullReclaimDoesNotEnableTakeover() throws Exception {
        var request = mapper.readValue("{\"token\":\"" + token + "\",\"reclaimSameOwner\":null}",
            ProcessingLeaseController.TokenRequest.class);
        assertThat(Boolean.TRUE.equals(request.reclaimSameOwner())).isFalse();
    }
}
