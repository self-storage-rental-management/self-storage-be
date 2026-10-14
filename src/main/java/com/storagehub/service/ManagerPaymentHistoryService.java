package com.storagehub.service;

import com.storagehub.api.payment.ManagerPaymentHistoryQuery;
import com.storagehub.api.payment.ManagerPaymentHistoryResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerPaymentHistoryService {
    private final PaymentRepository paymentRepository;
    private final ReturnCaseRepository returnCaseRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;

    @Transactional(readOnly = true)
    public PageResponse<ManagerPaymentHistoryResponse> list(
        ActorPrincipal actor, ManagerPaymentHistoryQuery query, String correlationId
    ) {
        if (!actor.hasRole(RoleCode.MANAGER)) {
            throw ApiExceptions.forbidden("Only managers can read facility payment history");
        }
        authorizationService.require(actor, SystemPermission.VIEW_PAYMENTS);
        facilityScopeService.assertCanRead(actor, query.facilityId());

        long offset = (long) query.page() * query.size();
        int fetchSize = Math.toIntExact(offset + query.size());
        String pattern = query.search().isBlank()
            ? null
            : "%" + query.search().toLowerCase(Locale.ROOT) + "%";
        PageRequest fetch = PageRequest.of(0, fetchSize);
        Page<Payment> payments = paymentRepository.searchFacilityPaymentHistory(query.facilityId(), pattern, fetch);
        Page<ReturnCase> settlements = returnCaseRepository.searchFacilityPaymentSettlements(
            query.facilityId(), pattern, fetch
        );

        List<ManagerPaymentHistoryResponse> all = new ArrayList<>(
            payments.getContent().size() + settlements.getContent().size()
        );
        payments.getContent().stream().map(this::paymentRow).forEach(all::add);
        settlements.getContent().stream().map(this::settlementRow).forEach(all::add);
        all.sort(Comparator.comparing(ManagerPaymentHistoryResponse::occurredAt).reversed()
            .thenComparing(ManagerPaymentHistoryResponse::id, Comparator.reverseOrder()));

        int from = (int) Math.min(offset, all.size());
        int to = Math.min(from + query.size(), all.size());
        long totalItems = payments.getTotalElements() + settlements.getTotalElements();
        int totalPages = totalItems == 0 ? 0 : Math.toIntExact((totalItems + query.size() - 1) / query.size());
        return new PageResponse<>(
            all.subList(from, to),
            new PageResponse.Pagination(query.page(), query.size(), totalItems, totalPages, "occurredAt,desc"),
            correlationId
        );
    }

    private ManagerPaymentHistoryResponse paymentRow(Payment payment) {
        String customerName = payment.getReservation() != null
            ? payment.getReservation().getCustomer().getFullName()
            : payment.getRenewal().getRental().getCustomer().getFullName();
        String reference = firstNonBlank(
            payment.getGatewayTransactionNo(), payment.getGatewayIntentId(), payment.getId().toString()
        );
        String method = "MANUAL".equalsIgnoreCase(payment.getGatewayProvider())
            ? payment.getGatewayBankCode()
            : firstNonBlank(payment.getGatewayProvider(), payment.getGatewayBankCode());
        Instant occurredAt = payment.getPaidAt() != null ? payment.getPaidAt()
            : payment.getProcessedAt() != null ? payment.getProcessedAt() : payment.getCreatedAt();
        return new ManagerPaymentHistoryResponse(
            payment.getId().toString(), reference, customerName, payment.getPurpose().name(),
            payment.getStatus().name(), payment.getAmount(), payment.getCurrency(),
            payment.getRefundedAmount() == null ? BigDecimal.ZERO : payment.getRefundedAmount(),
            method, occurredAt
        );
    }

    private ManagerPaymentHistoryResponse settlementRow(ReturnCase returnCase) {
        boolean refund = returnCase.getNetRefundAmount() != null
            && returnCase.getNetRefundAmount().signum() > 0;
        BigDecimal amount = refund ? returnCase.getNetRefundAmount() : returnCase.getAmountDueFromCustomer();
        return new ManagerPaymentHistoryResponse(
            returnCase.getId().toString(),
            firstNonBlank(returnCase.getSettlementPaymentId(), "RETURN-" + returnCase.getId()),
            returnCase.getCustomer().getFullName(),
            refund ? "RETURN_REFUND" : "RETURN_SETTLEMENT",
            refund ? "REFUNDED" : "PAID",
            amount,
            "VND",
            BigDecimal.ZERO,
            null,
            returnCase.getSettlementPaidAt()
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
