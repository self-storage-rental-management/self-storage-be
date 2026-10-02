package com.storagehub.service;

import com.storagehub.domain.model.LoginHistory;
import com.storagehub.domain.repo.LoginHistoryRepository;
import com.storagehub.service.email.UserAgentParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginHistoryBackfillServiceTest {

    @Mock
    private LoginHistoryRepository loginHistoryRepository;

    @Test
    @DisplayName("Backfill in chunks: 1200 records with batchSize=500 processes 3 batches (500, 500, 200) all at page 0")
    void testBackfillLargeDataset1200RecordsWithBatchSize500() {
        LoginHistoryBackfillService service = new LoginHistoryBackfillService(loginHistoryRepository);
        ReflectionTestUtils.setField(service, "backfillEnabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 500);

        List<LoginHistory> batch1 = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            LoginHistory h = new LoginHistory();
            h.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0");
            batch1.add(h);
        }

        List<LoginHistory> batch2 = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            LoginHistory h = new LoginHistory();
            h.setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15) Firefox/122.0");
            batch2.add(h);
        }

        List<LoginHistory> batch3 = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            LoginHistory h = new LoginHistory();
            h.setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/605.1.15");
            batch3.add(h);
        }

        when(loginHistoryRepository.findByDeviceFingerprintIsNull(any(Pageable.class)))
            .thenReturn(batch1)
            .thenReturn(batch2)
            .thenReturn(batch3)
            .thenReturn(Collections.emptyList());

        int totalUpdated = service.backfillPending();

        assertEquals(1200, totalUpdated);
        verify(loginHistoryRepository, times(3)).saveAllAndFlush(anyList());

        // Verify that the query always asks for page 0 (does NOT increment page number)
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(loginHistoryRepository, times(4)).findByDeviceFingerprintIsNull(pageableCaptor.capture());

        for (Pageable pageable : pageableCaptor.getAllValues()) {
            assertEquals(0, pageable.getPageNumber(), "Backfill MUST always query page 0 because updated records leave the NULL set");
            assertEquals(500, pageable.getPageSize());
        }

        // Verify fingerprints were set for all items
        batch1.forEach(h -> assertEquals("Windows PC|Chrome", h.getDeviceFingerprint()));
        batch2.forEach(h -> assertEquals("Mac|Firefox", h.getDeviceFingerprint()));
        batch3.forEach(h -> assertEquals("iPhone|Safari", h.getDeviceFingerprint()));
    }

    @Test
    @DisplayName("Backfill handles weird, unparseable, blank, and null User-Agents with fallback fingerprint (never null)")
    void testBackfillWithWeirdOrUnparseableUserAgents() {
        LoginHistoryBackfillService service = new LoginHistoryBackfillService(loginHistoryRepository);
        ReflectionTestUtils.setField(service, "backfillEnabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 500);

        LoginHistory hNull = new LoginHistory();
        hNull.setUserAgent(null);

        LoginHistory hEmpty = new LoginHistory();
        hEmpty.setUserAgent("");

        LoginHistory hWhitespace = new LoginHistory();
        hWhitespace.setUserAgent("    ");

        LoginHistory hWeirdBot = new LoginHistory();
        hWeirdBot.setUserAgent("CustomInternalTool/1.0 (internal-bot)");

        LoginHistory hCurl = new LoginHistory();
        hCurl.setUserAgent("curl/8.4.0");

        LoginHistory hPython = new LoginHistory();
        hPython.setUserAgent("python-requests/2.31.0");

        when(loginHistoryRepository.findByDeviceFingerprintIsNull(any(Pageable.class)))
            .thenReturn(List.of(hNull, hEmpty, hWhitespace, hWeirdBot, hCurl, hPython))
            .thenReturn(Collections.emptyList());

        int totalUpdated = service.backfillPending();

        assertEquals(6, totalUpdated);

        // Verify all records received a non-null, non-blank fingerprint matching the unified fallback constant
        assertNotNull(hNull.getDeviceFingerprint());
        assertEquals(UserAgentParser.UNKNOWN_FINGERPRINT, hNull.getDeviceFingerprint());

        assertNotNull(hEmpty.getDeviceFingerprint());
        assertEquals(UserAgentParser.UNKNOWN_FINGERPRINT, hEmpty.getDeviceFingerprint());

        assertNotNull(hWhitespace.getDeviceFingerprint());
        assertEquals(UserAgentParser.UNKNOWN_FINGERPRINT, hWhitespace.getDeviceFingerprint());

        assertNotNull(hWeirdBot.getDeviceFingerprint());
        assertEquals(UserAgentParser.UNKNOWN_FINGERPRINT, hWeirdBot.getDeviceFingerprint());

        assertNotNull(hCurl.getDeviceFingerprint());
        assertEquals(UserAgentParser.UNKNOWN_FINGERPRINT, hCurl.getDeviceFingerprint());

        assertNotNull(hPython.getDeviceFingerprint());
        assertEquals(UserAgentParser.UNKNOWN_FINGERPRINT, hPython.getDeviceFingerprint());
    }

    @Test
    @DisplayName("Backfill loop safety: Aborts when cyclic records are detected to prevent infinite loop")
    void testBackfillLoopSafety() {
        LoginHistoryBackfillService service = new LoginHistoryBackfillService(loginHistoryRepository);
        ReflectionTestUtils.setField(service, "backfillEnabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 500);

        UUID fixedId = UUID.randomUUID();
        LoginHistory stuckRecord = new LoginHistory();
        ReflectionTestUtils.setField(stuckRecord, "id", fixedId);
        stuckRecord.setUserAgent("Some-UA");

        // Mock repository returning the exact same stuck record repeatedly
        when(loginHistoryRepository.findByDeviceFingerprintIsNull(any(Pageable.class)))
            .thenReturn(List.of(stuckRecord))
            .thenReturn(List.of(stuckRecord));

        int totalUpdated = service.backfillPending();

        // Must update once, detect cycle on second iteration, and safely exit
        assertEquals(1, totalUpdated);
        verify(loginHistoryRepository, times(1)).saveAllAndFlush(anyList());
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
