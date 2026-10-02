package com.storagehub.service;

import com.storagehub.domain.model.LoginHistory;
import com.storagehub.domain.repo.LoginHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginHistoryBackfillServiceTest {

    @Mock
    private LoginHistoryRepository loginHistoryRepository;

    @Test
    @DisplayName("Backfill in chunks: 5 records with batchSize=2 processes 3 batches and updates all records")
    void testBackfillInChunks() {
        LoginHistoryBackfillService service = new LoginHistoryBackfillService(loginHistoryRepository);
        ReflectionTestUtils.setField(service, "backfillEnabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 2);

        LoginHistory h1 = new LoginHistory();
        h1.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0");
        LoginHistory h2 = new LoginHistory();
        h2.setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15) Firefox/122.0");

        LoginHistory h3 = new LoginHistory();
        h3.setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/605.1.15");
        LoginHistory h4 = new LoginHistory();
        h4.setUserAgent("Mozilla/5.0 (Linux; Android 14) Chrome/120.0.0.0");

        LoginHistory h5 = new LoginHistory();
        h5.setUserAgent("Unknown Agent");

        when(loginHistoryRepository.findByDeviceFingerprintIsNull(any(Pageable.class)))
            .thenReturn(List.of(h1, h2))
            .thenReturn(List.of(h3, h4))
            .thenReturn(List.of(h5))
            .thenReturn(Collections.emptyList());

        int totalUpdated = service.backfillPending();

        assertEquals(5, totalUpdated);
        verify(loginHistoryRepository, times(3)).saveAllAndFlush(anyList());
        assertEquals("Windows PC|Chrome", h1.getDeviceFingerprint());
        assertEquals("Mac|Firefox", h2.getDeviceFingerprint());
        assertEquals("iPhone|Safari", h3.getDeviceFingerprint());
        assertEquals("Thiết bị Android|Chrome", h4.getDeviceFingerprint());
        assertEquals("Không xác định|Không xác định", h5.getDeviceFingerprint());
    }

    @Test
    @DisplayName("Backfill disabled: Does not query repository when backfillEnabled is false")
    void testBackfillDisabled() {
        LoginHistoryBackfillService service = new LoginHistoryBackfillService(loginHistoryRepository);
        ReflectionTestUtils.setField(service, "backfillEnabled", false);

        service.run(new DefaultApplicationArguments(new String[0]));

        verifyNoInteractions(loginHistoryRepository);
    }
}
