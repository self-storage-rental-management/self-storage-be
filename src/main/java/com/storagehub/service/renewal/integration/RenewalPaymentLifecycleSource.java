package com.storagehub.service.renewal.integration;

import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.RenewalPaymentExpiryService;
import com.storagehub.service.ledger.RenewalVnpaySettlementService;
import com.storagehub.service.renewal.RenewalSources;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Ready only when the concrete deadline reconciler and the receipt settlement adapter are active. */
@Component
@ConditionalOnProperty(name="storagehub.integration.renewal-payment-lifecycle.enabled",havingValue="true")
public class RenewalPaymentLifecycleSource implements RenewalSources.ApprovalLifecycleSource {
    private final VnpayProperties vnpay;
    private final ObjectProvider<RenewalPaymentExpiryService> expiry;
    private final ObjectProvider<RenewalVnpaySettlementService> settlement;

    public RenewalPaymentLifecycleSource(VnpayProperties vnpay,ObjectProvider<RenewalPaymentExpiryService> expiry,
        ObjectProvider<RenewalVnpaySettlementService> settlement) {
        this.vnpay=vnpay;this.expiry=expiry;this.settlement=settlement;
    }

    @Override public boolean ready(Rental rental) {
        return rental!=null&&rental.getId()!=null&&vnpay.isEnabled()
            &&"SANDBOX".equals(PaymentProvenance.environment(vnpay.getPayUrl(),vnpay.getApiUrl()))
            &&expiry.getIfAvailable()!=null&&settlement.getIfAvailable()!=null;
    }
}
