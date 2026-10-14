package com.storagehub.service.calendar;

import com.storagehub.api.renewal.ServiceCalendarPolicyResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceCalendarReadService {
    private final DuongResourceAccess access;
    private final FacilityRepository facilities;

    public ServiceCalendarPolicyResponse read(ActorPrincipal actor, UUID facilityId) {
        access.require(actor, RoleCode.STAFF, facilityId, FacilityScopeLevel.READ, SystemPermission.VIEW_RENTALS);
        var facility = facilities.findById(facilityId)
            .orElseThrow(() -> ApiExceptions.notFound("Facility not found"));
        return new ServiceCalendarPolicyResponse(facility.getId(), facility.getStatus(),
            ServiceCalendarPolicy.ZONE.getId(), ServiceCalendarPolicy.WORKING_DAYS,
            ServiceCalendarPolicy.OPEN, ServiceCalendarPolicy.CLOSE,
            ServiceCalendarPolicy.OPEN, ServiceCalendarPolicy.LAST_APPOINTMENT_START,
            ServiceCalendarPolicy.SLOT_STEP_MINUTES, StaffRenewalSigningCalendarSource.SIGNING_MINUTES,
            StaffRenewalSigningCalendarSource.MAXIMUM_CONCURRENT_PER_STAFF, "NOT_EVALUATED");
    }
}
