package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.overdue.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.persistence.RenewalPersistence;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static com.storagehub.service.overdue.OverdueSources.*;

/** Query/stream/projection unit tests only; source snapshots are TEST-ONLY, not DB integration. */
class OverdueServicePaginationTests {
    static final Instant NOW=Instant.parse("2026-10-09T03:00:00Z");
    EntityManager em;TypedQuery<Rental> query;FinancialSource finance;TermSource terms;
    OverdueService service;ActorPrincipal actor;Facility facility;List<Rental> rentals;AtomicBoolean closed;
    <T>T id(T e){ReflectionTestUtils.setField(e,"id",UUID.randomUUID());return e;}
    @SuppressWarnings("unchecked") <T>ObjectProvider<T> provider(T v){var p=(ObjectProvider<T>)mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(v);return p;}
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        em=mock(EntityManager.class);query=mock(TypedQuery.class);finance=mock(FinancialSource.class);terms=mock(TermSource.class);
        service=new OverdueService(em,new ObjectMapper(),mock(RenewalPersistence.class),mock(AuditLogService.class),provider(Clock.fixed(NOW,ZoneOffset.UTC)),provider(finance),provider(terms),provider(null),provider(null));
        facility=id(new Facility());facility.setName("TEST facility");rentals=new ArrayList<>();closed=new AtomicBoolean();
        for(int i=0;i<3;i++){
            var u=id(new User());u.setFullName("TEST Customer "+i);var unit=id(new StorageUnit());unit.setCode("TEST-A-"+i);
            var r=id(new Rental());r.setCustomer(u);r.setFacility(facility);r.setStorageUnit(unit);r.setContractEndDate(LocalDate.of(2026,10,5));rentals.add(r);
        }
        when(em.createQuery(anyString(),eq(Rental.class))).thenReturn(query);when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.getResultStream()).thenAnswer(i->rentals.stream().onClose(()->closed.set(true)));
        when(terms.term(any(),eq(NOW))).thenAnswer(i->{Rental r=i.getArgument(0);return Optional.of(new Term("TEST-term","TEST-v1",r.getContractEndDate(),3,6,7,8,NOW.plusSeconds(86400),NOW.plusSeconds(172800)));});
        when(finance.read(any(),eq(NOW))).thenAnswer(i->{Rental r=i.getArgument(0);return Optional.of(new Finance(NOW,List.of(new Obligation(r.getId(),r.getId(),NOW.minusSeconds(1),new BigDecimal("5"),"VND"))));});
        actor=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(SystemPermission.VIEW_RENTALS.code()),Map.of(facility.getId(),FacilityScopeLevel.READ));
    }
    OverdueQuery q(int page,int size,String kind,String search,String sort,boolean desc){return new OverdueQuery(page,size,null,kind,search,sort,desc);}
    @Test void casesNotRentalsAreCountedAndPagesMatchFullOrdering(){
        var all=service.list(actor,q(0,100,"ALL","","caseRef",false),"TEST");assertThat(all.data()).hasSize(6);
        for(int p=0;p<4;p++){
            var result=service.list(actor,q(p,2,"ALL","","caseRef",false),"TEST");int from=Math.min(p*2,6);
            assertThat(result.data()).containsExactlyElementsOf(all.data().subList(from,Math.min(from+2,6)));
            assertThat(result.pagination().totalItems()).isEqualTo(6);assertThat(result.pagination().totalPages()).isEqualTo(3);
        }
        assertThat(closed).isTrue();verify(query,never()).setFirstResult(anyInt());verify(query,never()).setMaxResults(anyInt());
        verify(query,atLeastOnce()).setParameter("ids",List.of(facility.getId()));
    }
    @Test void searchAndKindAreAppliedBeforeTotalAndPaging(){
        var r=service.list(actor,q(0,1,"PAYMENT_DUE","Customer 1","caseRef",false),"TEST");
        assertThat(r.data()).hasSize(1);assertThat(r.data().getFirst().rentalId()).isEqualTo(rentals.get(1).getId());assertThat(r.pagination().totalItems()).isEqualTo(1);
    }
    @Test void missingSourcesFromLaterRentalsRemainVisibleEvenOnFirstPage(){
        when(terms.term(rentals.getLast(),NOW)).thenReturn(Optional.empty());
        var result=service.list(actor,q(0,1,"ALL","","priority",true),"TEST");
        assertThat(result.completeness()).isEqualTo("PARTIAL");assertThat(result.missingSources()).contains("RENTAL_TERM_POLICY_DATE_CUTOFF");
        assertThat(result.pagination().totalItems()).isEqualTo(5);verify(terms).term(rentals.getLast(),NOW);
    }
    @Test void descendingPrimarySortKeepsAscendingStableCaseReferenceTieBreaker(){
        var all=service.list(actor,q(0,100,"ALL","","priority",true),"TEST");
        var first=service.list(actor,q(0,2,"ALL","","priority",true),"TEST");
        assertThat(first.data()).containsExactlyElementsOf(all.data().subList(0,2));
        assertThat(all.data().stream().filter(r->r.kind().equals("PAYMENT_DUE")).map(OverdueResponse::caseRef).toList()).isSorted();
    }
    @Test void failedOwnerSourceStillClosesCursorAndDoesNotReturnPartialFakeSuccess(){
        when(finance.read(rentals.get(1),NOW)).thenThrow(new IllegalStateException("TEST-owner-outage"));
        assertThatThrownBy(()->service.list(actor,q(0,1,"ALL","","priority",true),"TEST")).hasMessage("TEST-owner-outage");assertThat(closed).isTrue();
    }
    @Test void foreignFacilityFailsBeforeQueryOrSourceCalls(){
        var q=new OverdueQuery(0,20,UUID.randomUUID(),"ALL","","priority",true);
        assertThatThrownBy(()->service.list(actor,q,"TEST")).isInstanceOf(ApiException.class);verifyNoInteractions(terms,finance);verify(em,never()).createQuery(anyString(),eq(Rental.class));
    }
}
