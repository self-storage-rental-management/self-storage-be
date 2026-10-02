package com.storagehub.service;

import com.storagehub.domain.model.LoginHistory;
import com.storagehub.domain.repo.LoginHistoryRepository;
import com.storagehub.service.email.UserAgentParser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoginHistoryBackfillService implements ApplicationRunner {

    private final LoginHistoryRepository loginHistoryRepository;

    @Value("${app.login-history.backfill-fingerprints:true}")
    private boolean backfillEnabled;

    @Value("${app.login-history.backfill-batch-size:500}")
    private int batchSize = 500;

    @Override
    public void run(ApplicationArguments args) {
        if (!backfillEnabled) {
            log.info("Login history device fingerprint backfill is disabled by configuration.");
            return;
        }
        backfillPending();
    }

    @Transactional
    public int backfillPending() {
        int totalUpdated = 0;
        int batchNumber = 1;

        while (true) {
            Pageable pageable = PageRequest.of(0, batchSize, Sort.by("id").ascending());
            List<LoginHistory> chunk = loginHistoryRepository.findByDeviceFingerprintIsNull(pageable);
            if (chunk.isEmpty()) {
                break;
            }

            for (LoginHistory history : chunk) {
                String ua = history.getUserAgent();
                String fp = UserAgentParser.parse(ua).fingerprint();
                history.setDeviceFingerprint(fp);
            }
            loginHistoryRepository.saveAllAndFlush(chunk);
            totalUpdated += chunk.size();
            log.info("Backfilled device fingerprints: batch {} ({} records in batch, total updated: {})",
                batchNumber, chunk.size(), totalUpdated);
            batchNumber++;
        }

        if (totalUpdated > 0) {
            log.info("Completed backfill of device fingerprints: {} total records updated.", totalUpdated);
        } else {
            log.info("No legacy login history records found requiring fingerprint backfill.");
        }
        return totalUpdated;
    }
}
