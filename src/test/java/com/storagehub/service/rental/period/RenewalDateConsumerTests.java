package com.storagehub.service.rental.period;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.*;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class RenewalDateConsumerTests {
    Rental r;ActorPrincipal customer;RenewalWorkflowService service;RenewalPersistence store;
    RenewalSources.PricingSource pricing;RenewalSources.PolicySource policy;RentalPeriodResolver periods;
    final UUID pack=UUID.randomUUID();final Instant now=Instant.parse("2026-10-15T03:00:00Z");
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void setup() throws Exception {
        var u=id(new User());var f=id(new Facility());var type=id(new UnitType());type.setFacility(f);
        var unit=id(new StorageUnit());unit.setFacility(f);unit.setUnitType(type);
        r=id(new Rental());r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);
        r.setStartDate(LocalDate.of(2026,10,1));r.setContractEndDate(LocalDate.of(2026,11,1));
        customer=new ActorPrincipal(u.getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
        var repo=mock(RentalRepository.class);when(repo.findByIdAndCustomer_Id(r.getId(),u.getId())).thenReturn(Optional.of(r));
        store=mock(RenewalPersistence.class);when(store.lockRental(r.getId())).thenReturn(r);
        when(store.storeQuote(eq(r),eq(u),any(),any(),any())).thenAnswer(a -> id(new RenewalQuote(r,u,mapper.writeValueAsString(a.getArgument(2)),"test-only-hash",a.getArgument(3),a.getArgument(4))));
        pricing=mock(RenewalSources.PricingSource.class);policy=mock(RenewalSources.PolicySource.class);
        when(policy.read(eq(r),any())).thenReturn(Optional.of(new RenewalSources.Policy("TEST-BO","v1",Duration.ofMinutes(30),Duration.ofHours(24),30,new BigDecimal("0.2"),Set.of(pack))));
        when(pricing.read(eq(r),any())).thenReturn(Optional.of(List.of(new RenewalSources.Price(pack,"TEST","v1",type.getId(),1,new BigDecimal("100"),BigDecimal.ZERO,"VND",2))));
        RenewalSources.EligibilitySource eligibility=(r,n) -> Optional.of(new RenewalSources.Eligibility(true,false,true,now.plusSeconds(172800)));
        service=new RenewalWorkflowService(repo,mock(RenewalReadService.class),store,mock(EntityManager.class),mapper,mock(AuditLogService.class),
            provider(policy),provider(pricing),provider(eligibility),provider(null),provider(null),provider(null),provider(Clock.fixed(now,ZoneOffset.UTC)));
        periods=mock(RentalPeriodResolver.class);ReflectionTestUtils.setField(service,"periods",periods);proof(RentalPeriod.Convention.EXCLUSIVE);
    }
    <T extends BaseEntity>T id(T e){ReflectionTestUtils.setField(e,"id",UUID.randomUUID());return e;}
    @SuppressWarnings("unchecked") <T>ObjectProvider<T> provider(T value){var p=(ObjectProvider<T>)mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);return p;}
    void proof(RentalPeriod.Convention c){when(periods.require(r)).thenAnswer(a -> RentalPeriod.verified(r.getId(),r.getStartDate(),r.getContractEndDate(),c,"TEST-ONLY-proof").orElseThrow());}
    @Test void exclusiveQuoteStartsExactlyAtRawEndWithCanonicalInclusiveNewEndAndNoReprice() {
        var q=service.quote(customer,r.getId(),new RenewalCommands.Quote("TEST"));var t=q.terms();
        assertThat(t.oldEndDate()).isEqualTo(LocalDate.of(2026,11,1));assertThat(t.extensionStartDate()).isEqualTo(t.oldEndDate());
        assertThat(t.extensionEndExclusive()).isEqualTo(LocalDate.of(2026,12,1));assertThat(t.newEndDate()).isEqualTo(LocalDate.of(2026,11,30));
        assertThat(t.endDateConvention()).isEqualTo("EXCLUSIVE");assertThat(t.rentalStartDate()).isEqualTo(r.getStartDate());
        assertThat(t.totalAfterDiscount()).isEqualByComparingTo("100");assertThat(t.renewalDepositAmount()).isEqualByComparingTo("20");
        assertThat(r.getContractEndDate()).isEqualTo(LocalDate.of(2026,11,1));
        verify(pricing).read(r,LocalDate.of(2026,11,1));verify(policy).read(r,LocalDate.of(2026,11,1));
        RenewalPeriodCompatibility.require(periods.require(r),t);
    }
    @Test void inclusiveOwnerProofRetainsExistingCalculationAndQuoteFieldMeanings() {
        r.setContractEndDate(LocalDate.of(2026,10,31));proof(RentalPeriod.Convention.INCLUSIVE);
        var t=service.quote(customer,r.getId(),new RenewalCommands.Quote("TEST")).terms();
        assertThat(t.oldEndDate()).isEqualTo(LocalDate.of(2026,10,31));assertThat(t.extensionStartDate()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(t.newEndDate()).isEqualTo(LocalDate.of(2026,11,30));assertThat(t.endDateConvention()).isEqualTo("INCLUSIVE");
    }
    @Test void unknownDateProvenanceBlocksBeforePricingOrQuotePersistence() {
        when(periods.require(r)).thenThrow(com.storagehub.common.api.ApiExceptions.conflict("DEFERRED_SOURCE: date provenance unknown"));
        assertThatThrownBy(()->service.options(customer,r.getId())).hasMessageContaining("DEFERRED_SOURCE");
        verifyNoInteractions(pricing,policy);verify(store,never()).storeQuote(any(),any(),any(),any(),any());
    }
    @Test void availableDatesDoNotReplaceMissingBoPolicy() {
        when(policy.read(eq(r),any())).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.options(customer,r.getId())).hasMessageContaining("BO renewal policy missing");verifyNoInteractions(pricing);
    }
    @Test void exclusiveLegacySnapshotCannotSilentlyAcquireNewMeaning() {
        var t=service.quote(customer,r.getId(),new RenewalCommands.Quote("TEST")).terms();
        var legacy=new RenewalQuoteResponse.Terms(t.oldEndDate(),t.extensionStartDate(),t.extensionEndExclusive(),t.newEndDate(),t.unitTypeId(),t.pricingPackageCode(),
            t.packagePolicyRef(),t.packagePolicyVersion(),t.rentalMonths(),t.monthlyPrice(),t.discountRate(),t.subtotal(),t.discountAmount(),t.totalAfterDiscount(),
            t.renewalDepositAmount(),t.remainingRentalAmount(),t.currency(),t.renewalPolicyRef(),t.renewalPolicyVersion(),t.storageUnitId(),t.facilityId());
        assertThatThrownBy(()->RenewalPeriodCompatibility.require(periods.require(r),legacy)).hasMessageContaining("requote");
    }
    @Test void changedRawTupleOrConventionCannotReuseFrozenAcceptedTerms() {
        var t=service.quote(customer,r.getId(),new RenewalCommands.Quote("TEST")).terms();
        r.setContractEndDate(r.getContractEndDate().plusDays(1));
        assertThatThrownBy(()->RenewalPeriodCompatibility.require(periods.require(r),t)).hasMessageContaining("requote");
    }
}
