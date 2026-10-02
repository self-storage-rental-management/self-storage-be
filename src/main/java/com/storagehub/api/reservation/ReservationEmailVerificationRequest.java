package com.storagehub.api.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class ReservationEmailVerificationRequest {

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "code must contain exactly 6 digits")
    private String code;

    public ReservationEmailVerificationRequest() {
    }

    public ReservationEmailVerificationRequest(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
