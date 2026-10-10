package com.storagehub.service.ledger;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
public class LedgerQueryService {
    public record Charge(UUID id,UUID renewalId,String kind,BigDecimal amount,Instant dueAt,BigDecimal outstanding) {}
    public record Payment(UUID id,String method,BigDecimal amount,Instant receivedAt,boolean simulated) {}
    public record Refund(UUID id,BigDecimal amount,String status) {}
    /** Known rows are not a claim that ALL project obligations have been reconciled. */
    public record View(UUID rentalId,String completeness,String currency,Long revision,
        List<Charge> obligations,List<Payment> receipts,List<Refund> refunds,String reason) {}
    private final LedgerStore ledger;
    private final EntityManager em;
    private final DuongResourceAccess access;
    public View read(ActorPrincipal actor,UUID id,RoleCode role) {
        if(actor==null)throw ApiExceptions.unauthorized("Authentication required");
        var rental=em.find(Rental.class,id);if(rental==null||rental.getCustomer()==null||rental.getFacility()==null)throw ApiExceptions.notFound("Rental not found");
        if(role==RoleCode.CUSTOMER){access.require(actor,role,null,FacilityScopeLevel.READ);if(!rental.getCustomer().getId().equals(actor.userId()))throw ApiExceptions.notFound("Rental not found");}
        else access.require(actor,role,rental.getFacility().getId(),FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS,SystemPermission.VIEW_PAYMENTS);
        var stored=ledger.read(id);if(stored.isPresent()&&(!stored.get().customerId().equals(rental.getCustomer().getId())||!stored.get().facilityId().equals(rental.getFacility().getId())))throw ApiExceptions.conflict("Ledger resource binding changed");
        return stored.map(s->new View(id,"PARTIAL","VND",s.revision(),
            s.obligations().stream().map(o->new Charge(o.id(),o.renewalId(),o.kind().name(),o.amount(),o.dueAt(),o.outstanding())).toList(),
            s.receipts().stream().map(p->new Payment(p.id(),p.method().name(),p.amount(),p.receivedAt(),p.method()==LedgerStore.Method.SIMULATED)).toList(),
            s.refunds().stream().map(f->new Refund(f.id(),f.amount(),f.status())).toList(),
            "Chỉ hiển thị các khoản đã được ghi nhận, chưa xác minh đủ toàn bộ dữ liệu tài chính."))
            .orElseGet(()->new View(id,"UNKNOWN","VND",null,null,null,null,"Chưa có dữ liệu sổ tài chính đã xác minh."));
    }
}
