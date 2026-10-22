package com.storagehub.api.renewal;

import jakarta.validation.constraints.*;
import java.util.*;

public final class RenewalInstallmentCommands {
    private RenewalInstallmentCommands(){}
    public record Quote(@NotEmpty @Size(max=100) List<@NotNull UUID> installmentIds){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Trường không hỗ trợ: "+key);}
    }
    public record Intent(@NotNull UUID paymentQuoteId){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Trường không hỗ trợ: "+key);}
    }
    public record Receipt(@NotNull UUID paymentQuoteId,@NotBlank @Size(max=100) String transactionReference,
        @NotBlank String paymentMethod,@NotNull UUID receiptFileId,@NotNull Boolean received){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Trường không hỗ trợ: "+key);}
    }
}
