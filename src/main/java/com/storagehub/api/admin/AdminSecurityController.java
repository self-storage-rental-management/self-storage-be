package com.storagehub.api.admin;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.AdminSecurityService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSecurityController {

    private final ActorContext actorContext;
    private final AdminSecurityService adminSecurityService;

    @GetMapping("/login-history")
    public PageResponse<AdminLoginHistoryResponse> loginHistory(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Boolean success,
        @RequestParam(required = false) UUID userId
    ) {
        return adminSecurityService.loginHistory(
            actorContext.required(), page, size, search, success, userId, CorrelationIdContext.current()
        );
    }

    @GetMapping("/sessions")
    public PageResponse<AdminSessionResponse> sessions(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) UUID userId
    ) {
        return adminSecurityService.sessions(
            actorContext.required(), page, size, userId, CorrelationIdContext.current()
        );
    }

    @PatchMapping("/sessions/{id}/revoke")
    public ApiResponse<AdminSessionResponse> revokeSession(@PathVariable UUID id) {
        return new ApiResponse<>(
            adminSecurityService.revokeSession(actorContext.required(), id),
            CorrelationIdContext.current()
        );
    }
}
