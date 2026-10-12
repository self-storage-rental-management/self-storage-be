package com.storagehub.config;

import com.storagehub.service.ledger.LedgerCoverageSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Startup diagnostics do not certify money, modify data or print merchant credentials. */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.renewal-payment-lifecycle.enabled", havingValue="true")
public class RenewalPaymentRuntimeDiagnostics implements ApplicationRunner {
    private final VnpayProperties vnpay;
    private final ObjectProvider<LedgerCoverageSource> financialCoverage;

    @Override
    public void run(ApplicationArguments args) {
        if (!vnpay.isEnabled() || vnpay.getTmnCode().isBlank() || vnpay.getHashSecret().isBlank()) {
            log.warn("Renewal payment unavailable: shared Payment VNPay merchant configuration is missing");
        }
        var coverage = financialCoverage.getIfAvailable();
        if (coverage == null || !coverage.consistentThroughApproval()) {
            log.warn("Renewal payment unavailable: a writer-backed LedgerCoverageSource is required to verify historical obligations, security deposit and disputes; enabling adapters does not certify this coverage");
        }
    }
}
