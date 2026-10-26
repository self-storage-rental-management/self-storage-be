package com.storagehub.service.renewal.integration;

import com.storagehub.api.renewal.PublishedRenewalPolicy;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.service.overdue.RenewalOverdueTimeline;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Runtime resolution only: the catalog fallback is never presented as a BO publication. */
@Component @RequiredArgsConstructor @Transactional(readOnly = true)
public class EffectiveRenewalPolicySource {
    private final RenewalPolicyPublicationService publications;
    private final RentalPackagePolicyRepository packages;

    public record Resolved(String reference, String version, Duration paymentWindow, BigDecimal depositRate,
        Set<UUID> eligiblePackageIds, PublishedRenewalPolicy.Signing signing, PublishedRenewalPolicy.Term term) {
        public static Resolved published(RenewalPolicyPublicationService.Stored stored) {
            var p = stored.policy();
            return new Resolved(stored.reference(), p.version(), Duration.ofHours(p.paymentWindowHours()), p.depositRate(),
                p.eligiblePackageIds(), p.signing(), RenewalOverdueTimeline.policy());
        }
    }

    public Optional<Resolved> read(Rental rental, LocalDate date) {
        if (rental == null || rental.getFacility() == null || date == null) return Optional.empty();
        UUID facility = rental.getFacility().getId();
        try {
            var published = publications.read(facility, date);
            if (published != null && published.isPresent()) return published.map(Resolved::published);
        } catch (ApiException invalidPolicy) {
            // An absent/malformed renewal setting does not hide valid shared rental packages.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Using facility rental packages: renewal policy unavailable for {}", facility);
        }
        if (packages == null) return Optional.empty();
        var eligible = packages.findByFacility_IdOrderByRentalMonthsAsc(facility).stream()
            .filter(p -> p != null && p.getFacility() != null && facility.equals(p.getFacility().getId())
                && p.getId() != null && p.isActive() && p.getPublicationStatus() != PublicationStatus.DRAFT
                && p.getEffectiveFrom() != null && !date.isBefore(p.getEffectiveFrom())
                && (p.getEffectiveTo() == null || !date.isAfter(p.getEffectiveTo())))
            .sorted(Comparator.comparing(p -> p.getId().toString())).toList();
        var ids = new LinkedHashSet<UUID>();
        var fingerprint = new StringBuilder("FACILITY_RENTAL_PACKAGES_V1:").append(facility);
        for (var pack : eligible) {
            ids.add(pack.getId());
            fingerprint.append(':').append(pack.getId()).append(':').append(pack.getPolicyVersion());
        }
        String version = UUID.nameUUIDFromBytes(fingerprint.toString().getBytes(StandardCharsets.UTF_8)).toString();
        return Optional.of(new Resolved("facility-rental-packages:" + facility, version, Duration.ofMinutes(15),
            new BigDecimal("0.20"), Set.copyOf(ids), new PublishedRenewalPolicy.Signing(3 * 24 * 60, 24 * 60),
            RenewalOverdueTimeline.policy()));
    }
}
