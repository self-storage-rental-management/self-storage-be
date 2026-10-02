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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoginHistoryBackfillService implements ApplicationRunner {

    private final LoginHistoryRepository loginHistoryRepository;

    @Value("${app.login-history.backfill-fingerprints:true}")
    private boolean backfillEnabled;

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
        List<LoginHistory> unpopulated = loginHistoryRepository.findAllByDeviceFingerprintIsNullAndUserAgentIsNotNull();
        if (unpopulated.isEmpty()) {
            return 0;
        }

        log.info("Starting backfill of device fingerprints for {} legacy login history records...", unpopulated.size());
        int updated = 0;
        for (LoginHistory history : unpopulated) {
            String ua = history.getUserAgent();
            if (ua != null && !ua.isBlank()) {
                String fp = UserAgentParser.parse(ua).fingerprint();
                history.setDeviceFingerprint(fp);
                updated++;
            }
        }
        loginHistoryRepository.saveAllAndFlush(unpopulated);
        log.info("Completed backfill of device fingerprints: {} records updated.", updated);
        return updated;
    }
}
