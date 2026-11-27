package com.storagehub.api.returns;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ReturnFeeScheduleService;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AdminAuthorizationService;
import com.storagehub.service.FacilityScopeService;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/return-fee-schedule")
@RequiredArgsConstructor
public class ReturnFeeScheduleController {
    private final ReturnFeeScheduleService schedule;
    private final ActorContext actors;
    private final ReturnCaseRepository returns;
    private final RentalRepository rentals;
    private final AdminAuthorizationService authorization;
    private final FacilityScopeService scopes;

    @GetMapping
    public ApiResponse<ReturnFeeScheduleService.Schedule> get() {
        actors.required();
        return new ApiResponse<>(schedule.get(), CorrelationIdContext.current());
    }

    @PutMapping
    public ApiResponse<ReturnFeeScheduleService.Schedule> save(@RequestBody ReturnFeeScheduleService.Schedule input) {
        var actor = actors.required();
        if (!actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.ADMIN)) throw ApiExceptions.forbidden("Không có quyền chỉnh sửa biểu phí.");
        return new ApiResponse<>(schedule.save(input, actor.userId()), CorrelationIdContext.current());
    }

    @GetMapping("/returns/{returnId}")
    @Transactional(readOnly = true)
    public ApiResponse<ReturnFeeScheduleService.Schedule> forReturn(@PathVariable UUID returnId) {
        var actor = actors.required();
        // Query by ownership before loading the case for customers. Facility read
        // access alone deliberately does not grant customers access to other returns.
        var rc = (actor.hasRole(RoleCode.CUSTOMER)
            ? returns.findByIdAndCustomer_Id(returnId, actor.userId()) : returns.findById(returnId))
            .orElseThrow(() -> ApiExceptions.notFound("Return case was not found"));
        if (!actor.hasRole(RoleCode.CUSTOMER)) requireStaffRead(actor, rc.getFacility().getId());
        return new ApiResponse<>(schedule.applicableTo(rc.getRental()), CorrelationIdContext.current());
    }

    @GetMapping("/rentals/{rentalId}")
    @Transactional(readOnly = true)
    public ApiResponse<ReturnFeeScheduleService.Schedule> forRental(@PathVariable UUID rentalId) {
        var actor = actors.required();
        Rental rental = rentals.findById(rentalId).orElseThrow(() -> ApiExceptions.notFound("Rental was not found"));
        if (actor.hasRole(RoleCode.CUSTOMER)) {
            if (!rental.getCustomer().getId().equals(actor.userId())) throw ApiExceptions.notFound("Rental was not found");
        } else requireStaffRead(actor, rental.getFacility().getId());
        return new ApiResponse<>(schedule.applicableTo(rental), CorrelationIdContext.current());
    }

    private void requireStaffRead(ActorPrincipal actor, UUID facilityId) {
        authorization.require(actor, SystemPermission.VIEW_RETURNS);
        scopes.assertCanRead(actor, facilityId);
    }
}
