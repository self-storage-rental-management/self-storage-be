package com.storagehub.api.renewal;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

public record RenewalQuoteResponse(UUID id,UUID rentalId,UUID customerId,@com.fasterxml.jackson.annotation.JsonUnwrapped Terms terms,Instant quotedAt,Instant expiresAt) {
    public record PlannedPeriod(int sequence,LocalDate periodStart,LocalDate periodEndExclusive,BigDecimal amount,BigDecimal prepaidAmount,BigDecimal remainingAmount,LocalDate dueDate){}
    @com.fasterxml.jackson.annotation.JsonProperty("dueAtSigning")
    public BigDecimal dueAtSigning(){return terms.dueAtSigning();}
    @com.fasterxml.jackson.annotation.JsonProperty("paymentSchedule")
    public java.util.List<PlannedPeriod> paymentSchedule(){
        var entries=new java.util.ArrayList<PlannedPeriod>();
        for(int i=0;i<(terms.monthly()?terms.rentalMonths():1);i++){
            var amount=terms.monthly()?terms.monthlyPrice():terms.totalAfterDiscount();
            var prepayment=i==0?terms.renewalDepositAmount():BigDecimal.ZERO;
            var start=terms.extensionStartDate().plusMonths(i);
            entries.add(new PlannedPeriod(i+1,start,terms.monthly()?terms.extensionStartDate().plusMonths(i+1):terms.extensionEndExclusive(),amount,prepayment,amount.subtract(prepayment),i==0?null:start));
        }
        return java.util.List.copyOf(entries);
    }
    public record Terms(LocalDate oldEndDate,LocalDate extensionStartDate,LocalDate extensionEndExclusive,LocalDate newEndDate,
        UUID unitTypeId,String pricingPackageCode,UUID packagePolicyRef,String packagePolicyVersion,int rentalMonths,
        BigDecimal monthlyPrice,BigDecimal discountRate,BigDecimal subtotal,BigDecimal discountAmount,BigDecimal totalAfterDiscount,
        BigDecimal renewalDepositAmount,BigDecimal remainingRentalAmount,String currency,String renewalPolicyRef,String renewalPolicyVersion,
        UUID storageUnitId,UUID facilityId,String endDateConvention,LocalDate rentalStartDate,RenewalPaymentPlan paymentPlan) {
        public Terms(LocalDate oldEndDate,LocalDate extensionStartDate,LocalDate extensionEndExclusive,LocalDate newEndDate,
            UUID unitTypeId,String pricingPackageCode,UUID packagePolicyRef,String packagePolicyVersion,int rentalMonths,
            BigDecimal monthlyPrice,BigDecimal discountRate,BigDecimal subtotal,BigDecimal discountAmount,BigDecimal totalAfterDiscount,
            BigDecimal renewalDepositAmount,BigDecimal remainingRentalAmount,String currency,String renewalPolicyRef,String renewalPolicyVersion,
            UUID storageUnitId,UUID facilityId,String endDateConvention,LocalDate rentalStartDate) {
            this(oldEndDate,extensionStartDate,extensionEndExclusive,newEndDate,unitTypeId,pricingPackageCode,packagePolicyRef,
                packagePolicyVersion,rentalMonths,monthlyPrice,discountRate,subtotal,discountAmount,totalAfterDiscount,
                renewalDepositAmount,remainingRentalAmount,currency,renewalPolicyRef,renewalPolicyVersion,storageUnitId,facilityId,
                endDateConvention,rentalStartDate,null);
        }
        public boolean monthly(){return paymentPlan==RenewalPaymentPlan.MONTHLY;}
        public BigDecimal dueAtSigning(){return monthly()?monthlyPrice.subtract(renewalDepositAmount):remainingRentalAmount;}
        /** Legacy stored snapshots remain readable; commands require matching verified provenance. */
        public Terms(LocalDate oldEndDate,LocalDate extensionStartDate,LocalDate extensionEndExclusive,LocalDate newEndDate,
            UUID unitTypeId,String pricingPackageCode,UUID packagePolicyRef,String packagePolicyVersion,int rentalMonths,
            BigDecimal monthlyPrice,BigDecimal discountRate,BigDecimal subtotal,BigDecimal discountAmount,BigDecimal totalAfterDiscount,
            BigDecimal renewalDepositAmount,BigDecimal remainingRentalAmount,String currency,String renewalPolicyRef,String renewalPolicyVersion,
            UUID storageUnitId,UUID facilityId) {
            this(oldEndDate,extensionStartDate,extensionEndExclusive,newEndDate,unitTypeId,pricingPackageCode,packagePolicyRef,
                packagePolicyVersion,rentalMonths,monthlyPrice,discountRate,subtotal,discountAmount,totalAfterDiscount,
                renewalDepositAmount,remainingRentalAmount,currency,renewalPolicyRef,renewalPolicyVersion,storageUnitId,facilityId,null,null);
        }
    }
}
