package com.storagehub.api.configuration;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.DiscountCodeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business/discounts")
public class DiscountCodeController {

    private final ActorContext actorContext;
    private final DiscountCodeService discountCodeService;

    public DiscountCodeController(ActorContext actorContext, DiscountCodeService discountCodeService) {
        this.actorContext = actorContext;
        this.discountCodeService = discountCodeService;
    }

    @GetMapping
    public ApiResponse<List<DiscountCodeResponse>> listDiscounts(
        @RequestParam(required = false) UUID facilityId
    ) {
        return new ApiResponse<>(
            discountCodeService.listDiscounts(actorContext.required(), facilityId),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<DiscountCodeResponse> getDiscount(@PathVariable UUID id) {
        return new ApiResponse<>(
            discountCodeService.getDiscount(actorContext.required(), id),
            CorrelationIdContext.current()
        );
    }

    @PostMapping
    public ApiResponse<DiscountCodeResponse> createDiscount(
        @Valid @RequestBody DiscountCodeRequest request
    ) {
        return new ApiResponse<>(
            discountCodeService.createDiscount(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @PutMapping("/{id}")
    public ApiResponse<DiscountCodeResponse> updateDiscount(
        @PathVariable UUID id,
        @Valid @RequestBody DiscountCodeRequest request
    ) {
        return new ApiResponse<>(
            discountCodeService.updateDiscount(actorContext.required(), id, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<DiscountCodeResponse> publishDiscount(@PathVariable UUID id) {
        return new ApiResponse<>(
            discountCodeService.publishDiscount(actorContext.required(), id),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{id}/inactive")
    public ApiResponse<DiscountCodeResponse> inactiveDiscount(@PathVariable UUID id) {
        return new ApiResponse<>(
            discountCodeService.inactiveDiscount(actorContext.required(), id),
            CorrelationIdContext.current()
        );
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDiscount(@PathVariable UUID id) {
        discountCodeService.deleteDiscount(actorContext.required(), id);
        return new ApiResponse<>(null, CorrelationIdContext.current());
    }
}
