package com.storagehub.api.renewal;

import com.storagehub.domain.model.RenewalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Schema(description="Real persisted Renewal projection. Missing terms, financial checks and version are UNKNOWN/null, never synthesized.")
public record RenewalResponse(UUID id, UUID rentalId, Customer customer, Facility facility, Unit storageUnit,
    RenewalStatus status, String reviewState, LocalDate oldEndDate, LocalDate newEndDate,
    BigDecimal amount, String currency, Instant createdAt, Long version, UUID requestedBy,
    UUID acceptedQuoteId, Integer acceptedRevision, UUID reviewerId, Instant reviewedAt,
    String reviewReason, Instant approvedPaymentDeadline, UUID extensionHoldRef,
    FinancialCheck financialCheck, SystemVerification systemVerification,
    List<String> allowedActions, List<String> disabledReasons,
    @Schema(description="Immutable customer-accepted quote terms; null for legacy records. Not current catalog prices.")
    RenewalQuoteResponse.Terms acceptedTerms,
    @Schema(description="Persisted cancellation reason only; no inferred actor/time.") String cancellationReason,
    String displayCode,
    @Schema(description="Independent workflow, receivable and verified transaction read models; null on legacy command replays.")
    RenewalStatusSummary statusSummary, EffectivePeriod effectivePeriod) {
    public record EffectivePeriod(LocalDate startDate,LocalDate endExclusive,LocalDate newEndDate,boolean finalized) {}
    public RenewalResponse(UUID id,UUID rentalId,Customer customer,Facility facility,Unit storageUnit,
        RenewalStatus status,String reviewState,LocalDate oldEndDate,LocalDate newEndDate,BigDecimal amount,
        String currency,Instant createdAt,Long version,UUID requestedBy,UUID acceptedQuoteId,Integer acceptedRevision,
        UUID reviewerId,Instant reviewedAt,String reviewReason,Instant approvedPaymentDeadline,UUID extensionHoldRef,
        FinancialCheck financialCheck,SystemVerification systemVerification,List<String> allowedActions,List<String> disabledReasons,
        RenewalQuoteResponse.Terms acceptedTerms,String cancellationReason,String displayCode,RenewalStatusSummary statusSummary) {
        this(id,rentalId,customer,facility,storageUnit,status,reviewState,oldEndDate,newEndDate,amount,currency,createdAt,version,
            requestedBy,acceptedQuoteId,acceptedRevision,reviewerId,reviewedAt,reviewReason,approvedPaymentDeadline,extensionHoldRef,
            financialCheck,systemVerification,allowedActions,disabledReasons,acceptedTerms,cancellationReason,displayCode,statusSummary,null);
    }
    public RenewalResponse withEffectivePeriod(EffectivePeriod period) {
        return new RenewalResponse(id,rentalId,customer,facility,storageUnit,status,reviewState,oldEndDate,
            period!=null&&period.finalized()?period.newEndDate():newEndDate,amount,currency,createdAt,version,requestedBy,
            acceptedQuoteId,acceptedRevision,reviewerId,reviewedAt,reviewReason,approvedPaymentDeadline,extensionHoldRef,
            financialCheck,systemVerification,allowedActions,disabledReasons,acceptedTerms,cancellationReason,displayCode,statusSummary,period);
    }
    public RenewalResponse(UUID id,UUID rentalId,Customer customer,Facility facility,Unit storageUnit,
        RenewalStatus status,String reviewState,LocalDate oldEndDate,LocalDate newEndDate,BigDecimal amount,
        String currency,Instant createdAt,Long version,UUID requestedBy,UUID acceptedQuoteId,Integer acceptedRevision,
        UUID reviewerId,Instant reviewedAt,String reviewReason,Instant approvedPaymentDeadline,UUID extensionHoldRef,
        FinancialCheck financialCheck,SystemVerification systemVerification,List<String> allowedActions,List<String> disabledReasons,
        RenewalQuoteResponse.Terms acceptedTerms,String cancellationReason,String displayCode) {
        this(id,rentalId,customer,facility,storageUnit,status,reviewState,oldEndDate,newEndDate,amount,currency,createdAt,version,
            requestedBy,acceptedQuoteId,acceptedRevision,reviewerId,reviewedAt,reviewReason,approvedPaymentDeadline,extensionHoldRef,
            financialCheck,systemVerification,allowedActions,disabledReasons,acceptedTerms,cancellationReason,displayCode,null);
    }
    public RenewalResponse withStatusSummary(RenewalStatusSummary summary) {
        return new RenewalResponse(id,rentalId,customer,facility,storageUnit,status,reviewState,oldEndDate,newEndDate,amount,currency,
            createdAt,version,requestedBy,acceptedQuoteId,acceptedRevision,reviewerId,reviewedAt,reviewReason,approvedPaymentDeadline,
            extensionHoldRef,financialCheck,systemVerification,allowedActions,disabledReasons,acceptedTerms,cancellationReason,displayCode,summary,effectivePeriod);
    }
    public RenewalResponse(UUID id,UUID rentalId,Customer customer,Facility facility,Unit storageUnit,
        RenewalStatus status,String reviewState,LocalDate oldEndDate,LocalDate newEndDate,BigDecimal amount,
        String currency,Instant createdAt,Long version,UUID requestedBy,UUID acceptedQuoteId,Integer acceptedRevision,
        UUID reviewerId,Instant reviewedAt,String reviewReason,Instant approvedPaymentDeadline,UUID extensionHoldRef,
        FinancialCheck financialCheck,SystemVerification systemVerification,List<String> allowedActions,List<String> disabledReasons,
        RenewalQuoteResponse.Terms acceptedTerms,String cancellationReason) {
        this(id,rentalId,customer,facility,storageUnit,status,reviewState,oldEndDate,newEndDate,amount,currency,createdAt,version,
            requestedBy,acceptedQuoteId,acceptedRevision,reviewerId,reviewedAt,reviewReason,approvedPaymentDeadline,extensionHoldRef,
            financialCheck,systemVerification,allowedActions,disabledReasons,acceptedTerms,cancellationReason,null);
    }
    public record Customer(UUID id, String fullName) {}
    public record Facility(UUID id, String code, String name) {}
    public record Unit(UUID id, String code) {}
    public record FinancialCheck(String completeness, Instant checkedAt,
        List<UUID> blockingObligationRefs, Boolean hasUnresolvedDispute) {}
    @Schema(description="Automatic verification from currently integrated Rental, policy, eligibility and pricing sources. It does not imply payment readiness.")
    public record SystemVerification(String status, Instant checkedAt, List<String> verifiedChecks,
        List<String> blockingReasons) {}
}
