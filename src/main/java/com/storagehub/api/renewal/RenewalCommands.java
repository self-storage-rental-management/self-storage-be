package com.storagehub.api.renewal;

import jakarta.validation.constraints.*;
import java.util.UUID;
import java.time.LocalDate;
import io.swagger.v3.oas.annotations.media.Schema;

public final class RenewalCommands {
    private RenewalCommands() {}
    public record Quote(@NotBlank @Size(max=30) String pricingPackageCode, RenewalPaymentPlan paymentPlan, Boolean fullPayment, LocalDate startDate) {
        public Quote(String pricingPackageCode){this(pricingPackageCode,null,null,null);}
        public Quote(String pricingPackageCode,RenewalPaymentPlan paymentPlan){this(pricingPackageCode,paymentPlan,null,null);}
        public Quote(String pricingPackageCode,RenewalPaymentPlan paymentPlan,Boolean fullPayment){this(pricingPackageCode,paymentPlan,fullPayment,null);}
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);}
    }
    public record Submit(@NotNull UUID renewalQuoteId,@Size(max=2000) String note) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    public record Edit(@NotNull UUID renewalQuoteId,@Size(max=2000) String note,@NotNull @PositiveOrZero Long expectedVersion) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    public record Cancel(@NotBlank @Size(max=2000) String reason,@NotNull @PositiveOrZero Long expectedVersion) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    public enum DecisionType { APPROVE, REJECT }
    private static void reject(String field){throw new IllegalArgumentException("Unknown Renewal command field: "+field);}
    @Schema(description="Shared integration is deferred; never a synthetic option or quote")
    public record Option(String pricingPackageCode,int rentalMonths,String pricingPackageName) {
        @com.fasterxml.jackson.annotation.JsonProperty("paymentPlans")
        public java.util.List<RenewalPaymentPlan> paymentPlans(){return java.util.List.of(RenewalPaymentPlan.MONTHLY,RenewalPaymentPlan.FULL_TERM);}
        public Option(String pricingPackageCode,int rentalMonths) {
            this(pricingPackageCode,rentalMonths,null);
        }
    }
}
