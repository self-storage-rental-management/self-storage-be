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
        // Backfill automatic customer notices and assignment alerts for existing active cases.
        for (int page = 0; ; page++) {
            var pending = recoveries.findByStageNotInOrderByCreatedAtDesc(java.util.List.of(
                com.storagehub.domain.model.ViolationRecovery.Stage.COMPLETED,
                com.storagehub.domain.model.ViolationRecovery.Stage.CANCELLED), PageRequest.of(page, 100));
            for (var entry : pending) {
                try {
                    workflow.notifyPendingNotifications(entry.getId());
                } catch (RuntimeException ex) {
                    log.error("Could not notify pending recovery assignment {}", entry.getId(), ex);
                }
            }
            if (pending.size() < 100) break;
        }
    }
}
