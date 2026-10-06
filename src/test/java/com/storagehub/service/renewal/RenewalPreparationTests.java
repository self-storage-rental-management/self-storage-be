package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.storagehub.api.renewal.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;

class RenewalPreparationTests {
    @Test void commandRejectsUnknownAuthoritativeFields() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readValue("{\"pricingPackageCode\":\"MONTHLY\"}",RenewalCommands.Quote.class).pricingPackageCode()).isEqualTo("MONTHLY");
        assertThatThrownBy(()->mapper.readValue("{\"pricingPackageCode\":\"MONTHLY\",\"amount\":1}",RenewalCommands.Quote.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void approvedWholeMonthClampAndPricing(){
        var t=RenewalTermCalculator.calculate(LocalDate.of(2027,1,30),1,new BigDecimal("9500000"),new BigDecimal("0.03"),new BigDecimal("0.20"));
        assertThat(t.startDate()).isEqualTo(LocalDate.of(2027,1,31));assertThat(t.newEndDate()).isEqualTo(LocalDate.of(2027,2,27));
        assertThat(t.netRent()).isEqualByComparingTo("9215000");assertThat(t.depositAmount()).isEqualByComparingTo("1843000");
        assertThat(t.depositAmount().add(t.remainder())).isEqualByComparingTo(t.netRent());
        assertThat(RenewalTermCalculator.calculate(LocalDate.of(2028,1,30),1,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO).endExclusive()).isEqualTo(LocalDate.of(2028,2,29));
    }
    @Test void invalidSourceNeverInventsTerms(){
        assertThatThrownBy(()->RenewalTermCalculator.calculate(LocalDate.now(),0,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->RenewalTermCalculator.calculate(LocalDate.now(),1,BigDecimal.ONE,null,BigDecimal.ZERO)).isInstanceOf(ApiException.class);
    }
    @Test void queryWhitelistAndStableOrdering(){
        var p=new LinkedMultiValueMap<String,String>();var q=RenewalListQuery.parse(p,true);
        assertThat(q.sort().toString()).isEqualTo("createdAt: DESC,id: ASC");
        for(var key:List.of("needsAttention","customerId","paid")){
            p.clear();p.add(key,"true");assertThatThrownBy(()->RenewalListQuery.parse(p,true)).isInstanceOf(ApiException.class);
        }
        p.clear();p.add("page","0");p.add("page","1");assertThatThrownBy(()->RenewalListQuery.parse(p,true)).isInstanceOf(ApiException.class);
        p.clear();p.add("facilityId",UUID.randomUUID().toString());assertThatThrownBy(()->RenewalListQuery.parse(p,false)).isInstanceOf(ApiException.class);
    }
    @Test void permissionCannotUseAdminOrMissingManagerScope(){
        assertThatThrownBy(()->RenewalReadService.authorize(actor(RoleCode.ADMIN,Set.of("view_rentals"),Map.of()),true,false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->RenewalReadService.authorize(actor(RoleCode.MANAGER,Set.of("view_rentals"),Map.of()),true,false)).isInstanceOf(ApiException.class);
        var a=actor(RoleCode.MANAGER,Set.of("view_rentals"),Map.of(UUID.randomUUID(),FacilityScopeLevel.READ));
        assertThatThrownBy(()->RenewalReadService.authorize(a,true,true)).isInstanceOf(ApiException.class);
    }
    @Test void legacySnapshotIsUnknownNotCurrentRentalEnd(){
        var repo=mock(RenewalRepository.class);var r=rental();var n=new Renewal();
        n.setRental(r);n.setRequestedBy(r.getCustomer());n.setAmount(new BigDecimal("9500000"));n.setNewEndDate(LocalDate.of(2027,3,31));
        when(repo.findOne(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(Optional.of(n));
        var dto=new RenewalReadService(repo).detail(customer(r),UUID.randomUUID(),false);
        assertThat(dto.oldEndDate()).isNull();assertThat(dto.version()).isNull();assertThat(dto.financialCheck().hasUnresolvedDispute()).isNull();
        assertThat(dto.allowedActions()).isEmpty();assertThat(dto.reviewState()).isEqualTo("UNKNOWN");verify(repo,never()).save(any());
    }
    @Test void missingPolicyBlocksQuoteAndNeverWrites(){
        var r=rental();var repo=mock(RentalRepository.class);when(repo.findByIdAndCustomer_Id(r.getId(),r.getCustomer().getId())).thenReturn(Optional.of(r));
        var gateway=gateway(repo,r);
        assertThatThrownBy(()->gateway.quote(customer(r),r.getId(),new RenewalCommands.Quote("MONTHLY")))
            .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus().value()).isEqualTo(409);assertThat(e.getMessage()).contains("DEFERRED_SOURCE");});
        verify(repo,never()).save(any());
    }
    @Test void missingOwnerIs404BeforeDependencies(){
        var repo=mock(RentalRepository.class);when(repo.findByIdAndCustomer_Id(any(),any())).thenReturn(Optional.empty());
        var gateway=gateway(repo,null);
        assertThatThrownBy(()->gateway.options(actor(RoleCode.CUSTOMER,Set.of(),Map.of()),UUID.randomUUID()))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(404));
    }
    private <T> ObjectProvider<T> provider(){return mock(ObjectProvider.class);}
    private RenewalWorkflowService gateway(RentalRepository repo,Rental rental){
        var store=mock(com.storagehub.service.renewal.persistence.RenewalPersistence.class);
        when(store.lockRental(any())).thenReturn(rental);
        return new RenewalWorkflowService(repo,mock(RenewalReadService.class),store,mock(jakarta.persistence.EntityManager.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.storagehub.service.AuditLogService.class),provider(),provider(),provider(),provider(),provider(),provider());
    }
    private ActorPrincipal actor(RoleCode role,Set<String> permissions,Map<UUID,FacilityScopeLevel> scopes){return new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(role),permissions,scopes);}
    private ActorPrincipal customer(Rental r){return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());}
    private Rental rental(){
        var f=new Facility();ReflectionTestUtils.setField(f,"id",UUID.randomUUID());f.setCode("F");f.setName("Facility");
        var u=new User();ReflectionTestUtils.setField(u,"id",UUID.randomUUID());u.setFullName("Customer");
        var unit=new StorageUnit();ReflectionTestUtils.setField(unit,"id",UUID.randomUUID());unit.setFacility(f);unit.setCode("U");
        var r=new Rental();ReflectionTestUtils.setField(r,"id",UUID.randomUUID());r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setContractEndDate(LocalDate.of(2027,1,30));return r;
    }
}
