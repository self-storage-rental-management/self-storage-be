package com.storagehub.api.renewal;

import jakarta.validation.constraints.*;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public final class RenewalCommands {
    private RenewalCommands() {}
    public record Quote(@NotBlank @Size(max=30) String pricingPackageCode) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    public record Submit(@NotNull UUID renewalQuoteId,@Size(max=2000) String note) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    public record Edit(@NotNull UUID renewalQuoteId,@Size(max=2000) String note,@NotNull @PositiveOrZero Long expectedVersion) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    public record Cancel(@NotBlank @Size(max=2000) String reason,@NotNull @PositiveOrZero Long expectedVersion) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    public enum DecisionType { APPROVE, REJECT }
    public record Decision(@NotNull DecisionType decision,@Size(max=2000) String reason,@NotNull @PositiveOrZero Long expectedVersion) { @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){reject(key);} }
    private static void reject(String field){throw new IllegalArgumentException("Unknown Renewal command field: "+field);}
    @Schema(description="Shared integration is deferred; never a synthetic option or quote")
    public record Option(String pricingPackageCode,int rentalMonths) {}
}
