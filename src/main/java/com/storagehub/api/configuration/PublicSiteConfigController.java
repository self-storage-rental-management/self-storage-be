package com.storagehub.api.configuration;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.service.BusinessPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/site-config")
@RequiredArgsConstructor
public class PublicSiteConfigController {

    private final BusinessPolicyService businessPolicyService;

    @GetMapping
    public ApiResponse<PublicSiteConfigResponse> get() {
        return new ApiResponse<>(businessPolicyService.getPublicSiteConfig(), CorrelationIdContext.current());
    }
}
