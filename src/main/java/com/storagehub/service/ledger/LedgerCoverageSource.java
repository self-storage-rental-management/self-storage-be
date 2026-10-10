package com.storagehub.service.ledger;

import com.storagehub.domain.model.Rental;
import com.storagehub.service.RentalReadSources;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Certification from ALL shared financial writers, not a client-controlled COMPLETE flag.
 * No default bean. Ledger rows alone cannot certify historical coverage/disputes/security deposit.
 */
public interface LedgerCoverageSource {
    record Coverage(UUID rentalId,long ledgerRevision,String sourceRef,Instant verifiedAt,
        boolean securityDepositCovered,boolean disputesCovered,boolean unresolvedDispute,
        RentalReadSources.BillingMode billingMode) {}
    Optional<Coverage> verify(Rental rental,LedgerStore.Snapshot snapshot,Instant now);
    default boolean consistentThroughApproval(){return false;}
}
