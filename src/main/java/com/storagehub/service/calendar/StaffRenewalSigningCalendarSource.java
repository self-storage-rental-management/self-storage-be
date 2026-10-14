package com.storagehub.service.calendar;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.integration.DuongResourceAccess;
import com.storagehub.service.renewal.integration.RenewalAssignmentSource;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Team-confirmed rule: one Renewal customer per Staff, 30-minute signing service. */
@Component @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
@ConditionalOnProperty(name="storagehub.integration.renewal-signing-calendar.enabled",havingValue="true")
public class StaffRenewalSigningCalendarSource implements RenewalOperationSources.CalendarSource {
    public static final int SIGNING_MINUTES=30;
    public static final int MAXIMUM_CONCURRENT_PER_STAFF=1;
    private static final Duration LENGTH=Duration.ofMinutes(SIGNING_MINUTES);
    private final RenewalCalendarStore store;
    private final EntityManager em;
    private final DuongResourceAccess access;
    private final RenewalAssignmentSource assignments;

    @Override @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public boolean atomic() {return store.atomic();}

    @Override public Optional<Boolean> feasible(Rental rental,Instant now,Instant deadline) {
        if(rental==null||rental.getFacility()==null||now==null||deadline==null)return Optional.empty();
        UUID facility=rental.getFacility().getId();
        if(store.legacyAppointmentsUncovered(now))return Optional.empty();
        return Optional.of(store.facilityActive(facility)&&anySlot(eligibleStaff(facility),now,deadline,null));
    }
    @Override public boolean feasible(Renewal renewal,Instant now,Instant deadline) {
        var rental=renewal.getRental();UUID facility=rental.getFacility().getId();
        if(store.legacyAppointmentsUncovered(now))throw deferred("Historical signing appointments require calendar reconciliation");
        List<UUID> staff=eligibleStaff(facility);
        return store.facilityActive(facility)&&anySlot(staff,now,deadline,renewal.getId());
    }
    private boolean anySlot(List<UUID> staff,Instant now,Instant deadline,UUID own) {
        if(now==null||deadline==null||!deadline.isAfter(now)||Duration.between(now,deadline).compareTo(Duration.ofDays(366))>0)return false;
        Instant first=ServiceCalendarPolicy.nextAppointmentAt(now);
        if(!first.isAfter(now))first=ServiceCalendarPolicy.nextAppointmentAt(first.plus(LENGTH));
        for(UUID id:staff) {
            var busy=store.busy(id,first,deadline,own);
            for(Instant candidate=first;!candidate.plus(LENGTH).isAfter(deadline);candidate=ServiceCalendarPolicy.nextAppointmentAt(candidate.plus(LENGTH))) {
                Instant start=candidate,end=candidate.plus(LENGTH);
                if(busy.stream().noneMatch(i->i.start().isBefore(end)&&i.end().isAfter(start)))return true;
            }
        }
        return false;
    }
    @Override public Optional<RenewalOperationSources.Slot> slot(Renewal renewal,Instant start) {
        if(!ServiceCalendarPolicy.isAppointmentStart(start))return Optional.empty();
        UUID facility=renewal.getRental().getFacility().getId();
        if(!store.facilityActive(facility))return Optional.empty();
        Instant end=start.plus(LENGTH);
        if(eligibleStaff(facility).stream().noneMatch(staff->store.busy(staff,start,end,renewal.getId()).isEmpty()))return Optional.empty();
        return Optional.of(new RenewalOperationSources.Slot(reference(renewal,start),start,end));
    }
    @Override public void reserve(Renewal renewal,RenewalOperationSources.Slot slot,Instant now) {
        UUID facility=renewal.getRental().getFacility().getId();
        store.lockFacility(facility);
        if(slot==null||!slot.start().isAfter(now)||!slot.end().equals(slot.start().plus(LENGTH))||!ServiceCalendarPolicy.isAppointmentStart(slot.start())
            ||!reference(renewal,slot.start()).equals(slot.reference()))throw ApiExceptions.conflict("Signing slot changed");
        if(store.legacyAppointmentsUncovered(now))throw deferred("Historical signing appointments require calendar reconciliation");
        var workflow=em.find(com.storagehub.service.renewal.persistence.RenewalWorkflow.class,renewal.getId());
        if(workflow==null||workflow.getAcceptedRevision()==null)throw deferred("Accepted Renewal revision is unavailable");
        // A slot consumes one unit of Staff capacity automatically; no Manager assignment or Staff ownership gate.
        // Staff mutexes are acquired in stable order across facilities.
        for(UUID staff:eligibleStaff(facility)) {
            store.lockStaff(staff);
            if(store.busy(staff,slot.start(),slot.end(),renewal.getId()).isEmpty()) {
                store.reserve(renewal.getId(),facility,staff,workflow.getAcceptedRevision().getQuote().getId(),slot.start(),slot.end(),now);
                return;
            }
        }
        throw ApiExceptions.conflict("Signing slot was taken; select another slot");
    }
    @Override public void release(Renewal renewal,Instant now) {
        var booking=store.current(renewal.getId(),false);
        if(booking.isPresent()){store.lockStaff(booking.get().staffId());store.release(renewal.getId(),now);}
    }
    private List<UUID> eligibleStaff(UUID facility) {
        return em.createQuery("select s.user from UserFacilityScope s where s.facility.id=:f and s.scopeLevel in :levels",User.class)
            .setParameter("f",facility).setParameter("levels",List.of(FacilityScopeLevel.OPERATE,FacilityScopeLevel.MANAGE)).getResultList().stream()
            .filter(u->access.eligible(u,RoleCode.STAFF,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS))
            .map(User::getId).distinct().sorted().toList();
    }
    private String reference(Renewal n,Instant at) {
        return UUID.nameUUIDFromBytes((n.getId()+":"+n.getRental().getFacility().getId()+":"+at).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private static RuntimeException deferred(String message) {return ApiExceptions.conflict("DEFERRED_SOURCE: "+message);}
}
