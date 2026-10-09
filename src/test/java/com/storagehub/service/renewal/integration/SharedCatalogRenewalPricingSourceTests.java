package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SharedCatalogRenewalPricingSourceTests {
    private final UnitTypeRepository types = mock(UnitTypeRepository.class);
    private final RentalPackagePolicyRepository packages = mock(RentalPackagePolicyRepository.class);
    private final SharedCatalogRenewalPricingSource source = new SharedCatalogRenewalPricingSource(types, packages);
    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private Facility facility;
    private UnitType type;
    private Rental rental;
    private RentalPackagePolicy pack;

    @BeforeEach
    void setup() {
        facility = identified(new Facility());
        type = identified(new UnitType());
        type.setFacility(facility);
        type.setMonthlyPrice(new BigDecimal("5500000.00"));
        var unit = identified(new StorageUnit());
        unit.setFacility(facility);
        unit.setUnitType(type);
        rental = identified(new Rental());
        rental.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setMonthlyPrice(new BigDecimal("100.00"));
        pack = pack("BASE", 1);
        when(types.findById(type.getId())).thenReturn(Optional.of(type));
        when(packages.findByFacility_IdOrderByRentalMonthsAsc(facility.getId())).thenReturn(List.of(pack));
    }

    @Test void readsCurrentTypePriceNotOldRentalRateOrCurrencyMultiplier() {
        var price = source.read(rental, START).orElseThrow().getFirst();
        assertThat(price.monthlyPrice()).isEqualByComparingTo("5500000.00");
        assertThat(price.currency()).isEqualTo("VND");
        assertThat(price.moneyScale()).isEqualTo(2);
        assertThat(price.unitTypeId()).isEqualTo(type.getId());
        assertThat(price.packageId()).isEqualTo(pack.getId());
        assertThat(price.packageVersion()).isEqualTo("test-price-v1");
        assertThat(rental.getMonthlyPrice()).isEqualByComparingTo("100.00");
        verify(packages, never()).save(any());
        verify(types, never()).save(any());
    }

    @Test void effectiveDatesAreInclusiveAndEvaluatedAtExtensionStartNotToday() {
        pack.setEffectiveFrom(START);
        pack.setEffectiveTo(START);
        assertThat(source.read(rental, START).orElseThrow()).hasSize(1);
        assertThat(source.read(rental, START.minusDays(1)).orElseThrow()).isEmpty();
        assertThat(source.read(rental, START.plusDays(1)).orElseThrow()).isEmpty();
    }

    @Test void excludesInactiveExpiredAndFuturePackagesAndSortsDeterministically() {
        var inactive = pack("INACTIVE", 1); inactive.setActive(false);
        var expired = pack("EXPIRED", 1); expired.setEffectiveTo(START.minusDays(1));
        var future = pack("FUTURE", 1); future.setEffectiveFrom(START.plusDays(1));
        var longer = pack("LONG", 3); longer.setDiscountRate(new BigDecimal("0.1000"));
        var alternative = pack("ALTERNATIVE", 1);
        when(packages.findByFacility_IdOrderByRentalMonthsAsc(facility.getId()))
            .thenReturn(List.of(longer, inactive, future, pack, expired, alternative));
        var prices = source.read(rental, START).orElseThrow();
        assertThat(prices).extracting(p -> p.packageCode()).containsExactly("ALTERNATIVE", "BASE", "LONG");
        assertThat(prices.getLast().discountRate()).isEqualByComparingTo("0.10");
    }

    @Test void verifiedEmptyCatalogIsDifferentFromMissingUnitType() {
        when(packages.findByFacility_IdOrderByRentalMonthsAsc(facility.getId())).thenReturn(List.of());
        assertThat(source.read(rental, START)).contains(List.of());
        when(types.findById(type.getId())).thenReturn(Optional.empty());
        assertThat(source.read(rental, START)).isEmpty();
    }

    @Test void missingMappingOrStartIsUnknownNotZero() {
        assertThat(source.read(null, START)).isEmpty();
        assertThat(source.read(rental, null)).isEmpty();
        rental.getStorageUnit().setUnitType(null);
        assertThat(source.read(rental, START)).isEmpty();
        verifyNoInteractions(types, packages);
    }

    @Test void inactiveTypeIsNotQuoted() {
        type.setStatus(UnitTypeStatus.inactive);
        assertThat(source.read(rental, START)).isEmpty();
        verifyNoInteractions(packages);
    }

    @Test void facilityMismatchIsReportedWithoutRepair() {
        var wrong = identified(new Facility());
        rental.getStorageUnit().setFacility(wrong);
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class)
            .hasMessageContaining("facilities do not match");
        assertThat(rental.getStorageUnit().getFacility()).isSameAs(wrong);
        verifyNoInteractions(types, packages);
    }

    @Test void crossFacilityPackageIsRejected() {
        pack.setFacility(identified(new Facility()));
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class)
            .hasMessageContaining("Package does not belong");
    }

    @Test void invalidMoneyIsRejectedNotRoundedOrReplaced() {
        for (String amount : List.of("-1.00", "12.345", "1000000000000.00")) {
            type.setMonthlyPrice(new BigDecimal(amount));
            assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class);
            assertThat(type.getMonthlyPrice()).isEqualByComparingTo(amount);
        }
        type.setMonthlyPrice(null);
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class);
    }

    @Test void explicitZeroCatalogPriceIsPreservedNotUsedAsFallback() {
        type.setMonthlyPrice(BigDecimal.ZERO);
        assertThat(source.read(rental, START).orElseThrow().getFirst().monthlyPrice()).isEqualByComparingTo("0");
    }

    @Test void invalidDiscountPeriodVersionAndDatesAreRejected() {
        pack.setDiscountRate(new BigDecimal("1.01"));
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class);
        pack.setDiscountRate(BigDecimal.ZERO); pack.setRentalMonths(0);
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class);
        pack.setRentalMonths(1); pack.setPolicyVersion(" ");
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class);
        pack.setPolicyVersion("v1"); pack.setEffectiveTo(pack.getEffectiveFrom().minusDays(1));
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class);
    }

    @Test void duplicateCodesAreRejectedRatherThanSelectingOneSilently() {
        when(packages.findByFacility_IdOrderByRentalMonthsAsc(facility.getId()))
            .thenReturn(List.of(pack, pack("BASE", 3)));
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(ApiException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test void databaseFailurePropagatesInsteadOfReturningEmptyOrFallbackData() {
        when(packages.findByFacility_IdOrderByRentalMonthsAsc(facility.getId()))
            .thenThrow(new IllegalStateException("test-only database failure"));
        assertThatThrownBy(() -> source.read(rental, START)).isInstanceOf(IllegalStateException.class);
    }

    private RentalPackagePolicy pack(String code, int months) {
        var p = identified(new RentalPackagePolicy());
        p.setFacility(facility); p.setCode(code); p.setRentalMonths(months);
        p.setPolicyVersion("test-price-v1"); p.setEffectiveFrom(START.minusMonths(1));
        return p;
    }

    private static <T extends BaseEntity> T identified(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
