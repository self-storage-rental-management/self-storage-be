package com.storagehub.service.renewal;

import com.storagehub.api.renewal.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RenewalRepository;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.criteria.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalReadService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.BusinessRecordCodes displayCodes;
    private final RenewalRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<RenewalWorkflowService> workflows;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<RenewalStatusProjection> statuses;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.SUPPORTS)
    public PageResponse<RenewalResponse> list(ActorPrincipal actor,RenewalListQuery filter,boolean manager,String correlation) {
        authorize(actor,manager,false);
        if(manager && filter.facilityId()!=null) requireScope(actor,filter.facilityId(),FacilityScopeLevel.READ);
        var page=readCore(()->PageResponse.from(repository.findAll(scope(actor,manager).and(filters(filter,manager)),filter.pageable())
            .map(n->project(n,actor,manager)),correlation));
        return new PageResponse<>(page.data().stream().map(this::withStatuses).toList(),page.pagination(),page.correlationId());
    }
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.SUPPORTS)
    public RenewalResponse detail(ActorPrincipal actor,UUID id,boolean manager) {
        return withStatuses(readCore(()->project(find(actor,id,manager),actor,manager)));
    }
    private <T> T readCore(java.util.function.Supplier<T> body) {
        if(transactions==null)return body.get();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        return transaction.execute(status->body.get());
    }
    public Renewal find(ActorPrincipal actor,UUID id,boolean manager) {
        authorize(actor,manager,false);
        return repository.findOne(scope(actor,manager).and((r,q,c)->c.equal(r.get("id"),id)))
            .orElseThrow(()->ApiExceptions.notFound("Renewal was not found"));
    }
    public static void authorize(ActorPrincipal a,boolean manager,boolean write) {
        if(a==null)throw ApiExceptions.unauthorized("Authentication is required");
        if(!manager){if(!a.hasRole(RoleCode.CUSTOMER))throw ApiExceptions.forbidden("Customer role required");return;}
        if(!a.hasRole(RoleCode.MANAGER)||!a.hasPermission(write?SystemPermission.MANAGE_RENTALS:SystemPermission.VIEW_RENTALS))
            throw ApiExceptions.forbidden("Manager rental permission required");
        if(a.facilityScopes().values().stream().noneMatch(s->s!=null&&s.includes(write?FacilityScopeLevel.MANAGE:FacilityScopeLevel.READ)))
            throw ApiExceptions.forbidden("Facility scope required");
    }
    public static void requireScope(ActorPrincipal a,UUID id,FacilityScopeLevel level){
        var actual=a.facilityScopes().get(id);if(actual==null||!actual.includes(level))throw ApiExceptions.forbidden("Facility scope required");
    }
    private Specification<Renewal> scope(ActorPrincipal a,boolean manager){return (r,q,c)->{
        var rental=r.join("rental",JoinType.LEFT);
        if(q.getResultType()==Renewal.class){
            var fetch=r.fetch("rental",JoinType.LEFT);fetch.fetch("customer",JoinType.LEFT);fetch.fetch("facility",JoinType.LEFT);
            fetch.fetch("storageUnit",JoinType.LEFT).fetch("facility",JoinType.LEFT);r.fetch("requestedBy",JoinType.LEFT);
        }
        if(!manager)return c.equal(rental.get("customer").get("id"),a.userId());
        var ids=a.facilityScopes().entrySet().stream().filter(e->e.getValue()!=null&&e.getValue().includes(FacilityScopeLevel.READ)).map(Map.Entry::getKey).toList();
        return rental.get("facility").get("id").in(ids);
    };}
    private Specification<Renewal> filters(RenewalListQuery f,boolean manager){return (r,q,c)->{
        List<Predicate> p=new ArrayList<>();var rental=r.join("rental",JoinType.LEFT);
        if(f.status()!=null)p.add(c.equal(r.get("status"),f.status()));
        if(f.rentalId()!=null)p.add(c.equal(rental.get("id"),f.rentalId()));
        if(f.facilityId()!=null)p.add(c.equal(rental.get("facility").get("id"),f.facilityId()));
        if(!f.search().isEmpty()){
            String pattern="%"+f.search().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            List<Predicate> matches=new ArrayList<>();
            String codeKey=displayCodes==null?null:displayCodes.resource("GH",f.search());
            if(codeKey!=null)matches.add(c.equal(r.get("id"),UUID.fromString(codeKey.substring(3))));
            matches.add(c.like(c.lower(rental.join("storageUnit",JoinType.LEFT).get("code")),pattern,'\\'));
            matches.add(c.like(c.lower(rental.join("customer",JoinType.LEFT).get("fullName")),pattern,'\\'));
            try{UUID id=UUID.fromString(f.search());matches.add(c.equal(r.get("id"),id));matches.add(c.equal(rental.get("id"),id));}catch(IllegalArgumentException ignored){}
            p.add(c.or(matches.toArray(Predicate[]::new)));
        }return c.and(p.toArray(Predicate[]::new));
    };}
    private RenewalResponse project(Renewal n,ActorPrincipal actor,boolean manager){
        var service=workflows==null?null:workflows.getIfAvailable();
        return service==null?baseProjection(n):service.project(n,actor,manager);
    }
    private RenewalResponse withStatuses(RenewalResponse result) {
        // Core GET data is fully materialized before optional financial probes. Do not hold an
        // outer JDBC connection while each probe needs a new one (concurrent lists can exhaust the pool).
        // Command finish/replay still uses the original projection, within its own transaction.
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())return result;
        var statusSource=statuses==null?null:statuses.getIfAvailable();
        return statusSource==null?result:result.withStatusSummary(RenewalStatusProjection.applyVerification(
            statusSource.project(result.id(),java.time.Instant.now()),result.systemVerification(),result.reviewState()));
    }
    public RenewalResponse baseProjection(Renewal n){
        Rental r=n.getRental();
        if(r==null||r.getCustomer()==null||r.getFacility()==null||r.getStorageUnit()==null||n.getRequestedBy()==null
            ||!Objects.equals(n.getRequestedBy().getId(),r.getCustomer().getId())
            ||r.getStorageUnit().getFacility()==null||!Objects.equals(r.getFacility().getId(),r.getStorageUnit().getFacility().getId())
            ||n.getStatus()==null||n.getNewEndDate()==null||n.getAmount()==null||n.getAmount().signum()<0)
            throw ApiExceptions.conflict("Renewal core relationships are inconsistent");
        return new RenewalResponse(n.getId(),r.getId(),new RenewalResponse.Customer(r.getCustomer().getId(),r.getCustomer().getFullName()),
            new RenewalResponse.Facility(r.getFacility().getId(),r.getFacility().getCode(),r.getFacility().getName()),
            new RenewalResponse.Unit(r.getStorageUnit().getId(),r.getStorageUnit().getCode()),n.getStatus(),"UNKNOWN",
            null,n.getNewEndDate(),n.getAmount(),"VND",n.getCreatedAt(),null,n.getRequestedBy().getId(),
            null,null,null,null,null,null,null,new RenewalResponse.FinancialCheck("UNKNOWN",null,null,null),
            new RenewalResponse.SystemVerification("UNKNOWN",null,List.of(),List.of()),
            List.of(),List.of("Accepted snapshot, version and shared renewal integrations are not connected"),null,null,
            displayCodes==null?null:displayCodes.find("GH:"+n.getId()));
    }
}
