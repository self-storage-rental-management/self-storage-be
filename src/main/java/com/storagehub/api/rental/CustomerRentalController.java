package com.storagehub.api.rental;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.CustomerRentalService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/rentals")
@RequiredArgsConstructor
public class CustomerRentalController {

    private final ActorContext actorContext;
    private final CustomerRentalService rentalService;

    @GetMapping
    public PageResponse<CustomerRentalResponse> list(
        @RequestParam(required = false) RentalStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return rentalService.list(actorContext.required(), status, page, size, CorrelationIdContext.current());
    }
}
