package com.storagehub.service.renewal.operations;

import com.storagehub.domain.model.Renewal;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Supplemental D3 state; does not rename shared RenewalStatus/payment-attempt semantics. */
@Entity @Table(name="renewal_operation_states") @Getter
@NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class RenewalOperationState {
    public enum Phase { SIGNING, SIGNING_EXPIRED, PAYMENT_EXPIRED, COMPLETED }
    @Id private UUID id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="id") private Renewal renewal;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Phase phase;
    private UUID depositPaymentRef;
    private Instant depositPaidAt;
    private Instant originalSigningDeadline;
    private Instant effectiveSigningDeadline;
    private Instant recoveryCutoff;
    private UUID appointmentRef;
    private Instant appointmentStart;
    private Instant appointmentEnd;
    private UUID arrivalRef;
    private UUID confirmedExceptionRef;
    private UUID pendingExceptionRef;
    private UUID completedBy;
    private Instant completedAt;
    @Column(length=200) private String policyRef;
    @Column(length=100) private String policyVersion;
    @Version private long version;
    public RenewalOperationState(Renewal renewal) {this.renewal=renewal;}
    public void deposit(UUID ref,Instant paid,Instant deadline,Instant cutoff,String policy,String version) {
        phase=Phase.SIGNING;depositPaymentRef=ref;depositPaidAt=paid;originalSigningDeadline=deadline;
        effectiveSigningDeadline=deadline;recoveryCutoff=cutoff;policyRef=policy;policyVersion=version;
    }
    public void appointment(UUID ref,Instant start,Instant end) {appointmentRef=ref;appointmentStart=start;appointmentEnd=end;arrivalRef=null;}
    public void arrival(UUID ref) {arrivalRef=ref;}
    public void proposedException(UUID ref) {pendingExceptionRef=ref;}
    public void exception(UUID decision,Instant deadline,Instant cutoff,UUID appointment,Instant start,Instant end) {
        confirmedExceptionRef=decision;pendingExceptionRef=null;effectiveSigningDeadline=deadline;recoveryCutoff=cutoff;
        phase=Phase.SIGNING;appointment(appointment,start,end);
    }
    public void expire(boolean paid) {phase=paid?Phase.SIGNING_EXPIRED:Phase.PAYMENT_EXPIRED;}
    public void completed(UUID staff,Instant at) {phase=Phase.COMPLETED;completedBy=staff;completedAt=at;}
}
