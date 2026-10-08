package com.storagehub.service;

import com.storagehub.api.rental.CustomerRentalResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerRentalService {

    private final RentalRepository rentalRepository;
    private final AdminAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public PageResponse<CustomerRentalResponse> list(
        ActorPrincipal actor,
        RentalStatus status,
        int page,
        int size,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_RENTALS);
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customer actors can access customer rentals");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var rentals = status == null
            ? rentalRepository.findByCustomer_Id(actor.userId(), pageable)
            : rentalRepository.findByCustomer_IdAndStatus(actor.userId(), status, pageable);
        return PageResponse.from(rentals.map(this::toResponse), correlationId);
    }

    private CustomerRentalResponse toResponse(Rental rental) {
        return new CustomerRentalResponse(
            rental.getId(),
            rental.getReservation().getId(),
            rental.getFacility().getId(),
            rental.getFacility().getName(),
            rental.getFacility().getAddress(),
            rental.getStorageUnit().getId(),
            rental.getStorageUnit().getCode(),
            rental.getStatus(),
            rental.getStartDate(),
            rental.getContractEndDate(),
            rental.getMonthlyPrice(),
            rental.getActualReturnedAt(),
            rental.getCompletedAt(),
            rental.getCreatedAt(),
            rental.getUpdatedAt()
        );
    }
}
