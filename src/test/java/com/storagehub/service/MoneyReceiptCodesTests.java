package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MoneyReceiptCodesTests {
    @Test
    void retriesCodeCollisionAndReturnsPersistedCodeOnReplay() {
        var jdbc = mock(JdbcTemplate.class);
        var sourceId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(String.class), eq("PAYMENT"), eq(sourceId.toString())))
            .thenReturn(List.of(), List.of("PT-1234-5678-9ABC"), List.of("PT-1234-5678-9ABC"));
        var codes = new MoneyReceiptCodes(jdbc);

        assertThat(codes.issue("PAYMENT", sourceId, false)).isEqualTo("PT-1234-5678-9ABC");
        assertThat(codes.issue("PAYMENT", sourceId, false)).isEqualTo("PT-1234-5678-9ABC");
        verify(jdbc, times(3)).update(anyString(), eq("PAYMENT"), eq(sourceId.toString()),
            matches("PT-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"));
    }

    @Test
    void refundsUseDisbursementPrefix() {
        var jdbc = mock(JdbcTemplate.class);
        var sourceId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(String.class), eq("RETURN"), eq(sourceId.toString())))
            .thenReturn(List.of("PC-1234-5678-9ABC"));

        assertThat(new MoneyReceiptCodes(jdbc).issue("RETURN", sourceId, true)).startsWith("PC-");
        verify(jdbc).update(anyString(), eq("RETURN"), eq(sourceId.toString()),
            matches("PC-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"));
    }
}
