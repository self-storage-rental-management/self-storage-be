package com.storagehub.api.admin;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.AdminSettingsService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final ActorContext actorContext;
    private final AdminSettingsService adminSettingsService;

    @GetMapping
    public ApiResponse<List<AdminSettingResponse>> list() {
        return new ApiResponse<>(adminSettingsService.list(actorContext.required()), CorrelationIdContext.current());
    }

    @PatchMapping("/{key}")
    public ApiResponse<AdminSettingResponse> update(
        @PathVariable String key,
        @Valid @RequestBody AdminUpdateSettingRequest request
    ) {
        return new ApiResponse<>(
            adminSettingsService.update(actorContext.required(), key, request),
            CorrelationIdContext.current()
        );
    }
}
