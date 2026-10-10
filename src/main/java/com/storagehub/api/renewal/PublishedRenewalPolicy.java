package com.storagehub.api.renewal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Independent BO extension; existing config/package DTOs and defaults are unchanged. */
public record PublishedRenewalPolicy(String schema,UUID facilityId,long revision,String version,UUID publishedBy,
    Instant publishedAt,LocalDate effectiveFrom,LocalDate effectiveTo,int quoteTtlMinutes,int paymentWindowHours,
    int requestWindowDays,BigDecimal depositRate,Set<UUID> eligiblePackageIds,Signing signing,Term term) {
    public record Input(@NotNull @PositiveOrZero Long expectedRevision,@NotNull LocalDate effectiveFrom,LocalDate effectiveTo,
        @NotNull @Positive Integer quoteTtlMinutes,@NotNull @Positive Integer paymentWindowHours,
        @NotNull @PositiveOrZero Integer requestWindowDays,@NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal depositRate,
        @NotNull @Size(max=20) Set<@NotNull UUID> eligiblePackageIds,@Valid Signing signing,@Valid Term term) {
        @JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("Unknown policy field: "+k);}
    }
    public record Signing(@NotNull @Positive Integer signingWindowMinutes,@NotNull @Positive Integer exceptionExtensionLimitMinutes) {
        @JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("Unknown signing field: "+k);}
    }
    public record Term(@NotBlank String calendar,@NotBlank String timezone,@NotNull @Positive Integer warningThroughDay,
        @NotNull @Positive Integer seriousThroughDay,@NotNull @Positive Integer urgentThroughDay,@NotNull @Positive Integer recoveryFromDay,
        @NotNull LocalTime recoveryCutoffTime,@NotNull LocalTime recoveryStartTime) {
        @JsonAnySetter public void unknown(String k,Object v){throw new IllegalArgumentException("Unknown term field: "+k);}
    }
}
