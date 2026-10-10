package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.integration.RenewalPolicyPublicationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Tag(name="Chính sách gia hạn")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
@RequestMapping("/api/business/facilities/{facilityId}/renewal-policy")
public class RenewalPolicyPublicationController {
    private final ActorContext actors;
    private final RenewalPolicyPublicationService policies;
    @GetMapping public ApiResponse<PublishedRenewalPolicy> get(@PathVariable UUID facilityId){return new ApiResponse<>(policies.get(actors.required(),facilityId),CorrelationIdContext.current());}
    @PutMapping public ApiResponse<PublishedRenewalPolicy> publish(@PathVariable UUID facilityId,@Valid @RequestBody PublishedRenewalPolicy.Input input){return new ApiResponse<>(policies.publish(actors.required(),facilityId,input),CorrelationIdContext.current());}
}
