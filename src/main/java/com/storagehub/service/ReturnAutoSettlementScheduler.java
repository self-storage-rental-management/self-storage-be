package com.storagehub.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReturnAutoSettlementScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReturnAutoSettlementScheduler.class);
    private final ReturnSettlementService returnSettlementService;

    @Scheduled(
        initialDelayString = "${storagehub.return.auto-settlement.initial-delay-ms:30000}",
        fixedDelayString = "${storagehub.return.auto-settlement.delay-ms:60000}"
    )
    public void scanAndAutoSettle() {
        try {
            int count = returnSettlementService.autoSettleExpiredReturns();
            if (count > 0) {
                log.info("[Auto-Settlement SLA] Successfully auto-settled {} return case(s) past 72h window", count);
            }
        } catch (Exception e) {
            log.error("[Auto-Settlement SLA] Error scanning for expired return cases: {}", e.getMessage(), e);
        }
    }
}
