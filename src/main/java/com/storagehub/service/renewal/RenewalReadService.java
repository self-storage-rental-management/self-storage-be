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
    private final RenewalRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<RenewalWorkflowService> workflows;
    public PageResponse<RenewalResponse> list(ActorPrincipal actor,RenewalListQuery filter,boolean manager,String correlation) {
        authorize(actor,manager,false);
        if(manager && filter.facilityId()!=null) requireScope(actor,filter.facilityId(),FacilityScopeLevel.READ);
        return PageResponse.from(repository.findAll(scope(actor,manager).and(filters(filter,manager)),filter.pageable()).map(n->project(n,actor,manager)),correlation);
    }
    public RenewalResponse detail(ActorPrincipal actor,UUID id,boolean manager) { return project(find(actor,id,manager),actor,manager); }
    public Renewal find(ActorPrincipal actor,UUID id,boolean manager) {
        authorize(actor,manager,false);
        return repository.findOne(scope(actor,manager).and((r,q,c)->c.equal(r.get("id"),id)))
            .orElseThrow(()->ApiExceptions.notFound("Renewal was not found"));
    }
    public Renewal findForDecision(ActorPrincipal actor,UUID id) {
        authorize(actor,true,true);
        var ids=actor.facilityScopes().entrySet().stream().filter(e->e.getValue()!=null&&e.getValue().includes(FacilityScopeLevel.MANAGE)).map(Map.Entry::getKey).toList();
        return repository.findOne((r,q,c)->c.and(c.equal(r.get("id"),id),r.get("rental").get("facility").get("id").in(ids)))
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
        if(manager&&!f.search().isEmpty()){
            String pattern="%"+f.search().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            List<Predicate> matches=new ArrayList<>();
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
            List.of(),List.of("Accepted snapshot, version and shared renewal integrations are not connected"));
    }
}
