package com.storagehub.api.rental;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.RentalQueryService;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class ManagerRentalPaymentController {
    private final ActorContext actors;
    private final RentalQueryService rentals;
    private final DuongResourceAccess access;
    private final EntityManager em;
    private final PaymentProvenance provenance;
    public record Receipt(UUID paymentId,UUID rentalId,UUID renewalId,String purpose,String status,
        BigDecimal amount,String currency,BigDecimal refundedAmount,String transactionReference,String gatewayTransactionNo,
        String paymentMethod,String gatewayPayDate,Instant paidAt,Instant processedAt,Instant createdAt,
        String initiatedByName,String verification) {}
    @GetMapping("/api/manager/rentals/{id}/payments") @Transactional(readOnly=true)
    public ApiResponse<List<Receipt>> read(@PathVariable UUID id) {
        var actor=actors.required();
        var rental=rentals.detail(actor,id,true);
        access.require(actor,RoleCode.MANAGER,rental.facility().id(),FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS,SystemPermission.VIEW_PAYMENTS);
        var rows=em.createQuery("select p from Payment p left join p.reservation reservation left join p.renewal n where (reservation.id=:reservation or n.rental.id=:rental) "
            +"and p.purpose in :purposes order by p.createdAt desc,p.id desc",Payment.class)
            .setParameter("reservation",rental.reservationId()).setParameter("rental",id)
            .setParameter("purposes",List.of(PaymentType.RESERVATION_DEPOSIT,PaymentType.RENTAL_BALANCE,PaymentType.MONTHLY_RENT,PaymentType.RENEWAL)).getResultList();
        var result=rows.stream().map(p->new Receipt(p.getId(),id,p.getRenewal()==null?null:p.getRenewal().getId(),p.getPurpose().name(),p.getStatus().name(),
            p.getAmount(),p.getCurrency(),p.getRefundedAmount(),p.getGatewayIntentId(),p.getGatewayTransactionNo(),p.getGatewayBankCode(),
            p.getGatewayPayDate(),p.getPaidAt(),p.getProcessedAt(),p.getCreatedAt(),p.getInitiatedBy().getFullName(),provenance.classify(p).name())).toList();
        return new ApiResponse<>(result,CorrelationIdContext.current());
    }
}
