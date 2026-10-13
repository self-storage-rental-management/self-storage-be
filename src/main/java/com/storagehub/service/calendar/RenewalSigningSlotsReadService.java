package com.storagehub.service.calendar;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Renewal;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.renewal.integration.LocalDemoSigningCalendarSource;
import com.storagehub.service.renewal.operations.RenewalOperationService;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalSigningSlotsReadService {
    private final EntityManager em;
    private final RenewalOperationService operations;
    private final ObjectProvider<RenewalOperationSources.CalendarSource> calendars;
    private final ObjectProvider<Clock> clocks;
    public record View(UUID renewalId,Long expectedVersion,Instant checkedAt,LocalDate date,
        String sourceCoverage,List<RenewalOperationSources.Slot> slots) {}

    public View read(ActorPrincipal actor,UUID id,LocalDate date,RenewalOperationService.Audience audience) {
        var detail=operations.detail(actor,id,audience); // Existing ownership/current Staff assignment authorization.
        if(!Set.of("SIGNING","AWAITING_PAYMENT_INTENT").contains(detail.phase())||detail.arrivalRef()!=null)
            throw ApiExceptions.conflict("Renewal is not available for signing appointment selection");
        var clock=clocks.getIfAvailable();Instant now=(clock==null?Clock.systemUTC():clock).instant();
        if(date==null||date.isBefore(now.atZone(ServiceCalendarPolicy.ZONE).toLocalDate()))
            throw ApiExceptions.validation("Signing date must be today or later",null);
        var source=calendars.getIfAvailable();
        boolean prepayment="AWAITING_PAYMENT_INTENT".equals(detail.phase());
        var window=prepayment?operations.appointmentWindow(actor,id,audience):new Instant[]{detail.effectiveSigningDeadline(),detail.recoveryCutoff()};
        if(source==null||!source.atomic()||window[0]==null||window[1]==null)
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Signing calendar/deadline/Recovery cutoff unavailable");
        Instant deadline=window[0].isBefore(window[1])?window[0]:window[1];
        if(!deadline.isAfter(now)||date.isAfter(deadline.atZone(ServiceCalendarPolicy.ZONE).toLocalDate()))
            throw ApiExceptions.conflict("Signing date is outside the current deadline");
        var renewal=em.find(Renewal.class,id);
        boolean demo=LocalDemoSigningCalendarSource.class.isAssignableFrom(org.springframework.aop.support.AopUtils.getTargetClass(source));
        String coverage=demo?"LOCAL_DEMO":"VERIFIED";
        if(!source.feasible(renewal,now,deadline))return new View(id,detail.expectedVersion(),now,date,coverage,List.of());
        var result=new ArrayList<RenewalOperationSources.Slot>();
        for(LocalTime time=ServiceCalendarPolicy.OPEN;!time.isAfter(ServiceCalendarPolicy.LAST_APPOINTMENT_START);time=time.plusMinutes(ServiceCalendarPolicy.SLOT_STEP_MINUTES)) {
            Instant at=date.atTime(time).atZone(ServiceCalendarPolicy.ZONE).toInstant();
            if(!at.isAfter(now)||!at.isBefore(deadline))continue;
            var candidate=source.slot(renewal,at);
            if(candidate.isPresent()) {
                var slot=candidate.get();
                if(slot.reference()==null||slot.reference().isBlank()||!at.equals(slot.start())||slot.end()==null||!slot.end().isAfter(at))
                    throw ApiExceptions.conflict("DEFERRED_SOURCE: Signing slot source returned inconsistent data");
                if(!slot.end().isAfter(deadline))result.add(slot);
            }
        }
        return new View(id,detail.expectedVersion(),now,date,coverage,List.copyOf(result));
    }
}
