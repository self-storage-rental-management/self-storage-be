package com.storagehub.api.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CancelReservationRequest {

    @NotBlank
    @Size(max = 1000)
    private String reason;

    public CancelReservationRequest() {
    }

    public CancelReservationRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
