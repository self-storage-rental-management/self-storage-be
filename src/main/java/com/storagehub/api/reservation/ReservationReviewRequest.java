package com.storagehub.api.reservation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ReservationReviewRequest {

    @NotNull
    private ReservationReviewDecision decision;

    @Size(max = 1000)
    private String note;

    public ReservationReviewRequest() {
    }

    public ReservationReviewRequest(ReservationReviewDecision decision, String note) {
        this.decision = decision;
        this.note = note;
    }

    public ReservationReviewDecision getDecision() { return decision; }
    public void setDecision(ReservationReviewDecision decision) { this.decision = decision; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
