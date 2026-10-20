package com.storagehub.api.renewal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.security.ActorContext;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.renewal.operations.RenewalOperationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class RenewalPaymentReceiptController {
    private final ActorContext actors;
    private final RenewalOperationService operations;
    private final PaymentRepository payments;
    private final PaymentProvenance provenance;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.ledger.RenewalInstallmentService> installments;
    public record Receipt(UUID paymentId,UUID renewalId,String transactionReference,String gatewayTransactionNo,
        BigDecimal amount,String currency,String paymentStatus,String paymentMethod,String gatewayPayDate,
        Instant paidAt,Instant processedAt,String initiatedByName,String kind,String environment) {}
    @GetMapping("/api/{role:customer|manager|staff}/renewals/{id}/payments/{paymentId}/receipt")
    @Transactional(readOnly=true)
    public ApiResponse<Receipt> read(@PathVariable String role,@PathVariable UUID id,@PathVariable UUID paymentId) {
        var installmentService=installments==null?null:installments.getIfAvailable();
        if(installmentService!=null)installmentService.read(actors.required(),id,paymentId,role);
        else operations.detail(actors.required(),id,RenewalOperationService.Audience.valueOf(role.toUpperCase(Locale.ROOT)));
        var payment=payments.findById(paymentId).orElseThrow(()->ApiExceptions.notFound("Renewal payment not found"));
        if(payment.getRenewal()==null||!id.equals(payment.getRenewal().getId()))throw ApiExceptions.notFound("Renewal payment not found");
        if(payment.getStatus()!=PaymentStatus.PAID||payment.getRenewalLedgerReceiptRef()==null||payment.getRenewalAllocatedAt()==null)
            throw ApiExceptions.conflict("Reconciled renewal payment receipt is not available");
        String kind="REMAINING_RENT";
        if(payment.getPurpose()==PaymentType.RENEWAL) {
            try {
                var terms=mapper.readTree(payment.getRenewalRevision().getQuote().getTermsJson());
                kind=terms.path("renewalDepositAmount").decimalValue().signum()>0?"DEPOSIT":"FULL_RENT";
            }catch(Exception invalid){throw ApiExceptions.conflict("Renewal payment terms are unavailable");}
        }else if(payment.getPurpose()==PaymentType.RENEWAL_INSTALLMENT)kind="INSTALLMENT";
        else if(payment.getPurpose()!=PaymentType.RENTAL_BALANCE)throw ApiExceptions.notFound("Renewal payment receipt not found");
        var classification=provenance.classify(payment);
        if(!Set.of(PaymentProvenance.Classification.SANDBOX,PaymentProvenance.Classification.VERIFIED).contains(classification))
            throw ApiExceptions.conflict("Renewal payment evidence requires reconciliation");
        String environment=classification==PaymentProvenance.Classification.SANDBOX?"SANDBOX":"PRODUCTION";
        return new ApiResponse<>(new Receipt(payment.getId(),id,payment.getGatewayIntentId(),payment.getGatewayTransactionNo(),
            payment.getAmount(),payment.getCurrency(),payment.getStatus().name(),payment.getGatewayBankCode(),payment.getGatewayPayDate(),
            payment.getPaidAt(),payment.getProcessedAt(),payment.getInitiatedBy().getFullName(),kind,environment),CorrelationIdContext.current());
    }
}
