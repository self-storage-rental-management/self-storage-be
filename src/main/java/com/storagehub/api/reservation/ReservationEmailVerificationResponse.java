package com.storagehub.api.reservation;

import com.storagehub.domain.model.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public class ReservationEmailVerificationResponse {

    private UUID reservationId;
    private ReservationStatus reservationStatus;
    private boolean verified;
    private Instant expiresAt;
    private Instant nextResendAt;
    private String developmentCode;

    public ReservationEmailVerificationResponse() {
    }

    public ReservationEmailVerificationResponse(
        UUID reservationId,
        ReservationStatus reservationStatus,
        boolean verified,
        Instant expiresAt,
        Instant nextResendAt,
        String developmentCode
    ) {
        this.reservationId = reservationId;
        this.reservationStatus = reservationStatus;
        this.verified = verified;
        this.expiresAt = expiresAt;
        this.nextResendAt = nextResendAt;
        this.developmentCode = developmentCode;
    }

    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public ReservationStatus getReservationStatus() { return reservationStatus; }
    public void setReservationStatus(ReservationStatus reservationStatus) { this.reservationStatus = reservationStatus; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getNextResendAt() { return nextResendAt; }
    public void setNextResendAt(Instant nextResendAt) { this.nextResendAt = nextResendAt; }
    public String getDevelopmentCode() { return developmentCode; }
    public void setDevelopmentCode(String developmentCode) { this.developmentCode = developmentCode; }
}
