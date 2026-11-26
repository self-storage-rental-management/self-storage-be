package com.storagehub.service;

import com.storagehub.domain.repo.ViolationRecoveryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ViolationRecoveryScheduler {
    private final ViolationRecoveryRepository recoveries;
    private final ViolationRecoveryWorkflow workflow;

    @Scheduled(initialDelayString = "${storagehub.recovery.initial-delay-ms:60000}",
        fixedDelayString = "${storagehub.recovery.delay-ms:60000}")
    public void process() {
        var now = Instant.now();
        for (var id : recoveries.due(now, PageRequest.of(0, 100))) {
            try {
                workflow.processDue(id, now);
            } catch (RuntimeException ex) {
                log.error("Could not advance recovery {}", id, ex);
            }
        }
    }
}
