package com.storagehub.service.renewal.integration;

import com.storagehub.domain.model.Rental;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Authoritative composite coverage; no default/flag can prove Recovery and signing feasibility. */
public interface RenewalEligibilityEvidenceSource {
    record Evidence(UUID rentalId, UUID unitId, UUID facilityId, Instant checkedAt,
                    boolean recoveryBlocked, boolean feasible, String policyRef,
                    String policyVersion, Instant recoveryCutoff) {
        /** Legacy providers remain compatible but cannot certify a policy-bound positive result. */
        public Evidence(UUID rentalId, UUID unitId, UUID facilityId, Instant checkedAt,
                        boolean recoveryBlocked, boolean feasible) {
            this(rentalId, unitId, facilityId, checkedAt, recoveryBlocked, feasible, null, null, null);
        }
    }
    /** Complete current Recovery and signing/calendar coverage for this rental at the supplied instant. */
    Optional<Evidence> read(Rental rental, Instant now);
}
