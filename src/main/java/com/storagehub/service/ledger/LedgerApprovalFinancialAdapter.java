package com.storagehub.service.ledger;

import com.storagehub.domain.model.Rental;
import com.storagehub.service.renewal.RenewalSources;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
public class LedgerApprovalFinancialAdapter implements RenewalSources.FinancialSource {
    private final LedgerFinancialAdapters adapters;
    public Optional<RenewalSources.Financial> read(Rental rental){return adapters.approval(rental);}
    public boolean consistentThroughApproval(){return adapters.consistentThroughApproval();}
}
