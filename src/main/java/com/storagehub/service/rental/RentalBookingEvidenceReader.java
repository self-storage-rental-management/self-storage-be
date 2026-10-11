package com.storagehub.service.rental;

import com.storagehub.api.rental.RentalBookingEvidence;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads existing shared records without posting/backfilling ledger or inventing coverage/access. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RentalBookingEvidenceReader {
    private final EntityManager em;
    public Optional<RentalBookingEvidence> read(ActorPrincipal actor,Rental rental,boolean manager) {
        if(actor==null)throw ApiExceptions.unauthorized("Authentication required");
        if(rental==null || rental.getCustomer()==null || rental.getFacility()==null || rental.getReservation()==null)
            throw ApiExceptions.conflict("Rental booking relationship incomplete");
        if(manager) {
            if(!actor.hasRole(RoleCode.MANAGER) || !actor.hasPermission(SystemPermission.VIEW_RENTALS))
                throw ApiExceptions.forbidden("Manager rental permission required");
            var scope=actor.facilityScopes().get(rental.getFacility().getId());
            if(scope==null || !scope.includes(FacilityScopeLevel.READ))throw ApiExceptions.notFound("Rental not found");
            // Rental read alone must not expose payment records through this additive field.
            if(!actor.hasPermission(SystemPermission.VIEW_PAYMENTS))return Optional.empty();
        } else if(!actor.hasRole(RoleCode.CUSTOMER) || !Objects.equals(actor.userId(),rental.getCustomer().getId()))
            throw ApiExceptions.notFound("Rental not found");
        var reservation=rental.getReservation();
        if(reservation.getCustomer()==null || reservation.getFacility()==null
            || !Objects.equals(reservation.getCustomer().getId(),rental.getCustomer().getId())
            || !Objects.equals(reservation.getFacility().getId(),rental.getFacility().getId()))
            throw ApiExceptions.conflict("Rental booking relationship changed");
        var snapshots=em.createQuery("select s from ReservationPricingSnapshot s where s.reservation.id=:id",ReservationPricingSnapshot.class)
            .setParameter("id",reservation.getId()).setMaxResults(2).getResultList();
        if(snapshots.size()>1)throw ApiExceptions.conflict("Booking pricing snapshot is ambiguous");
        RentalBookingEvidence.Amounts amounts=null;
        if(!snapshots.isEmpty()) {
            var s=snapshots.getFirst();
            if(!money(s.getNetRentalAmount()) || !money(s.getReservationDepositAmount()) || !money(s.getSecurityDepositAmount())
                || !money(s.getRemainingRentalAmount()) || !money(s.getDueAtCheckIn()) || !money(s.getTotalInitialObligation())
                || s.getReservationDepositAmount().add(s.getRemainingRentalAmount()).compareTo(s.getNetRentalAmount())!=0
                || s.getRemainingRentalAmount().add(s.getSecurityDepositAmount()).compareTo(s.getDueAtCheckIn())!=0
                || s.getNetRentalAmount().add(s.getSecurityDepositAmount()).compareTo(s.getTotalInitialObligation())!=0)
                throw ApiExceptions.conflict("Booking pricing snapshot totals are inconsistent");
            amounts=new RentalBookingEvidence.Amounts(s.getNetRentalAmount(),s.getReservationDepositAmount(),s.getSecurityDepositAmount(),
                s.getRemainingRentalAmount(),s.getDueAtCheckIn(),s.getTotalInitialObligation());
        }
        var rows=em.createQuery("select p from Payment p where p.reservation.id=:id order by p.createdAt desc,p.id desc",Payment.class)
            .setParameter("id",reservation.getId()).setMaxResults(21).getResultList();
        for(var p:rows)if(!"VND".equals(p.getCurrency()) || !money(p.getAmount()) || p.getPurpose()==null || p.getStatus()==null)
            throw ApiExceptions.conflict("Booking payment record is inconsistent");
        var payments=rows.stream().limit(20).map(p->new RentalBookingEvidence.PaymentRecord(p.getId(),p.getPurpose().name(),p.getStatus().name(),
            p.getAmount(),p.getProcessedAt(),p.getGatewayIntentId()!=null && p.getGatewayIntentId().startsWith("SIMULATED-")?"SIMULATED":"UNVERIFIED")).toList();
        // Do not return gateway intent, idempotency key, failure details or other internal identifiers.
        return Optional.of(new RentalBookingEvidence(rental.getId(),reservation.getId(),"VND",amounts,payments,rows.size()>20));
    }
    private static boolean money(BigDecimal value){return value!=null && value.signum()>=0;}
}
