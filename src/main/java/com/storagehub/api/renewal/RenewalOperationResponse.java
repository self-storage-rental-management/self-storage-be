package com.storagehub.api.renewal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public record RenewalOperationResponse(UUID renewalId,Long expectedVersion,String phase,
    UUID depositPaymentRef,Instant depositPaidAt,Instant originalSigningDeadline,Instant effectiveSigningDeadline,
    Instant recoveryCutoff,UUID appointmentRef,UUID arrivalRef,UUID confirmedExceptionRef,
    UUID completedBy,Instant completedAt,List<String> missingSources,
    Instant appointmentStart,Instant appointmentEnd,UUID pendingExceptionRef,
    UUID fullRentPaymentRef,Instant fullRentPaidAt,String displayCode,
    String rentalDisplayCode,Integer renewalSequence,AppointmentDetails appointmentDetails) {
    public record AppointmentDetails(String customerName,String customerEmail,LocalDate proposedEndDate) {}

    public RenewalOperationResponse(UUID renewalId,Long expectedVersion,String phase,UUID depositPaymentRef,Instant depositPaidAt,Instant originalSigningDeadline,Instant effectiveSigningDeadline,Instant recoveryCutoff,UUID appointmentRef,UUID arrivalRef,UUID confirmedExceptionRef,UUID completedBy,Instant completedAt,List<String> missingSources,Instant appointmentStart,Instant appointmentEnd,UUID pendingExceptionRef,UUID fullRentPaymentRef,Instant fullRentPaidAt) {this(renewalId,expectedVersion,phase,depositPaymentRef,depositPaidAt,originalSigningDeadline,effectiveSigningDeadline,recoveryCutoff,appointmentRef,arrivalRef,confirmedExceptionRef,completedBy,completedAt,missingSources,appointmentStart,appointmentEnd,pendingExceptionRef,fullRentPaymentRef,fullRentPaidAt,null,null,null,null);}

    public RenewalOperationResponse withAppointmentDetails(AppointmentDetails details) {
        return new RenewalOperationResponse(renewalId,expectedVersion,phase,depositPaymentRef,depositPaidAt,originalSigningDeadline,effectiveSigningDeadline,recoveryCutoff,appointmentRef,arrivalRef,confirmedExceptionRef,completedBy,completedAt,missingSources,appointmentStart,appointmentEnd,pendingExceptionRef,fullRentPaymentRef,fullRentPaidAt,displayCode,rentalDisplayCode,renewalSequence,details);
    }

    public record Event(UUID id,String kind,Instant occurredAt,UUID actorId,Object data) {}
}
