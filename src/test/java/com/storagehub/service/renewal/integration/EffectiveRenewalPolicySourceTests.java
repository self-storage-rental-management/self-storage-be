package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class EffectiveRenewalPolicySourceTests {
    @Test void missingOrInvalidPolicyUsesOnlyActiveEffectivePackages() {
        var publications=mock(RenewalPolicyPublicationService.class);
        var packages=mock(RentalPackagePolicyRepository.class);
        var facility=mock(Facility.class); var id=UUID.randomUUID(); when(facility.getId()).thenReturn(id);
        var rental=mock(Rental.class); when(rental.getFacility()).thenReturn(facility);
        var date=LocalDate.of(2026,10,26);
        when(publications.read(id,date)).thenReturn(Optional.empty());
        var valid=pack(facility,date); var inactive=pack(facility,date); inactive.setActive(false);
        var expired=pack(facility,date); expired.setEffectiveTo(date.minusDays(1));
        var future=pack(facility,date); future.setEffectiveFrom(date.plusDays(1));
        var draft=pack(facility,date); draft.setPublicationStatus(PublicationStatus.DRAFT);
        var otherFacility=mock(Facility.class);when(otherFacility.getId()).thenReturn(UUID.randomUUID());
        var foreign=pack(otherFacility,date);
        when(packages.findByFacility_IdOrderByRentalMonthsAsc(id)).thenReturn(List.of(valid,inactive,expired,future,draft,foreign));
        var source=new EffectiveRenewalPolicySource(publications,packages);
        var result=source.read(rental,date).orElseThrow();
        assertThat(result.eligiblePackageIds()).containsExactly(valid.getId());
        assertThat(result.reference()).isEqualTo("facility-rental-packages:"+id);
        assertThat(source.read(rental,date).orElseThrow().version()).isEqualTo(result.version());
        doThrow(com.storagehub.common.api.ApiExceptions.conflict("Published renewal policy is invalid")).when(publications).read(id,date);
        assertThat(source.read(rental,date).orElseThrow().eligiblePackageIds()).containsExactly(valid.getId());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable")).when(publications).read(id,date);
        assertThatThrownBy(()->source.read(rental,date)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }
    @Test void explicitEmptyPublishedWhitelistIsRespected() {
        var publications=mock(RenewalPolicyPublicationService.class);
        var packages=mock(RentalPackagePolicyRepository.class);
        var facility=mock(Facility.class); var id=UUID.randomUUID(); when(facility.getId()).thenReturn(id);
        var rental=mock(Rental.class); when(rental.getFacility()).thenReturn(facility);
        var policy=mock(com.storagehub.api.renewal.PublishedRenewalPolicy.class);
        when(policy.paymentWindowHours()).thenReturn(1);when(policy.eligiblePackageIds()).thenReturn(Set.of());
        var date=LocalDate.of(2026,10,26);
        when(publications.read(id,date)).thenReturn(Optional.of(new RenewalPolicyPublicationService.Stored("published",policy)));
        assertThat(new EffectiveRenewalPolicySource(publications,packages).read(rental,date).orElseThrow().eligiblePackageIds()).isEmpty();
        verifyNoInteractions(packages);
    }
    private RentalPackagePolicy pack(Facility facility,LocalDate date) {
        var p=new RentalPackagePolicy();org.springframework.test.util.ReflectionTestUtils.setField(p,"id",UUID.randomUUID());p.setFacility(facility);p.setActive(true);
        p.setPublicationStatus(PublicationStatus.PUBLISHED);p.setEffectiveFrom(date.minusDays(1));p.setPolicyVersion("v1");return p;
    }
}
