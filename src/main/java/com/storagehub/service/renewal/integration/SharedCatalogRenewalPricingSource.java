package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.BaseEntity;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.service.renewal.RenewalSources;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only bridge to the existing shared catalog, not a renewal-policy publisher.
 * RenewalWorkflowService must still intersect these prices with the BO policy's
 * eligiblePackageIds and enforce every eligibility/finance/hold/lifecycle gate.
 * VND, scale 2 and HALF_UP are the existing ReservationQuoteService and
 * RenewalTermCalculator money contract; no currency conversion is performed.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedCatalogRenewalPricingSource implements RenewalSources.PricingSource {
    private final UnitTypeRepository unitTypes;
    private final RentalPackagePolicyRepository packages;

    @Override
    public Optional<List<RenewalSources.Price>> read(Rental rental, LocalDate extensionStart) {
        if (rental == null || extensionStart == null || rental.getFacility() == null
            || rental.getFacility().getId() == null || rental.getStorageUnit() == null
            || rental.getStorageUnit().getUnitType() == null
            || rental.getStorageUnit().getUnitType().getId() == null) {
            return Optional.empty();
        }
        UUID facilityId = rental.getFacility().getId();
        var unit = rental.getStorageUnit();
        if (!same(facilityId, unit.getFacility()) || !same(facilityId, unit.getUnitType().getFacility())) {
            throw inconsistent("Rental/unit/type facilities do not match");
        }
        // Read the current catalog entity rather than reusing a detached Rental price snapshot.
        var found = unitTypes.findById(unit.getUnitType().getId());
        if (found.isEmpty()) return Optional.empty();
        var type = found.get();
        if (!same(unit.getUnitType().getId(), type) || !same(facilityId, type.getFacility())) {
            throw inconsistent("Current unit type does not match the Rental");
        }
        if (type.getStatus() != UnitTypeStatus.active) return Optional.empty();
        BigDecimal monthlyPrice = type.getMonthlyPrice();
        if (monthlyPrice == null || monthlyPrice.signum() < 0) {
            throw inconsistent("UnitType monthly price is missing or negative");
        }
        try {
            monthlyPrice = monthlyPrice.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw inconsistent("UnitType price exceeds the shared two-decimal money scale");
        }
        if (monthlyPrice.precision() > 14) throw inconsistent("UnitType price exceeds the shared money precision");

        var result = new ArrayList<RenewalSources.Price>();
        var codes = new HashSet<String>();
        var ids = new HashSet<UUID>();
        // Use the existing repository without adding queries/fields to the shared owner contract.
        for (RentalPackagePolicy pack : packages.findByFacility_IdOrderByRentalMonthsAsc(facilityId)) {
            if (pack == null || !same(facilityId, pack.getFacility())) {
                throw inconsistent("Package does not belong to the Rental facility");
            }
            if (!pack.isActive()) continue;
            if (pack.getEffectiveFrom() == null || pack.getEffectiveTo() != null
                && pack.getEffectiveTo().isBefore(pack.getEffectiveFrom())) {
                throw inconsistent("Active package effective dates are invalid");
            }
            if (pack.getEffectiveFrom().isAfter(extensionStart)
                || pack.getEffectiveTo() != null && pack.getEffectiveTo().isBefore(extensionStart)) continue;
            if (pack.getId() == null || !ids.add(pack.getId()) || pack.getCode() == null
                || pack.getCode().isBlank() || !pack.getCode().equals(pack.getCode().trim())
                || pack.getCode().length() > 30 || !codes.add(pack.getCode())
                || pack.getPolicyVersion() == null || pack.getPolicyVersion().isBlank()
                || pack.getRentalMonths() < 1 || pack.getDiscountRate() == null
                || pack.getDiscountRate().signum() < 0
                || pack.getDiscountRate().compareTo(BigDecimal.ONE) > 0) {
                throw inconsistent("Effective package price metadata is invalid or ambiguous");
            }
            result.add(new RenewalSources.Price(pack.getId(), pack.getCode(), pack.getPolicyVersion(),
                type.getId(), pack.getRentalMonths(), monthlyPrice, pack.getDiscountRate(), "VND", 2));
        }
        result.sort(Comparator.comparingInt(RenewalSources.Price::months)
            .thenComparing(RenewalSources.Price::packageCode));
        // Present-but-empty is an authoritative empty catalog, not an unavailable DB or invented package.
        return Optional.of(List.copyOf(result));
    }

    private static boolean same(UUID expected, BaseEntity entity) {
        return expected != null && entity != null && expected.equals(entity.getId());
    }

    private static RuntimeException inconsistent(String reason) {
        return ApiExceptions.conflict("Shared renewal pricing data is inconsistent: " + reason
            + "; no shared records were changed");
    }
}
