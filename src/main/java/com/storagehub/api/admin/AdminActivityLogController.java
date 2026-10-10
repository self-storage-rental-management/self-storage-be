package com.storagehub.api.admin;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.AdminActivityLogService;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/activity-logs")
@RequiredArgsConstructor
public class AdminActivityLogController {

    private final ActorContext actorContext;
    private final AdminActivityLogService adminActivityLogService;

    @GetMapping
    public PageResponse<AdminActivityLogResponse> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) UUID actorId,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to
    ) {
        return adminActivityLogService.list(
            actorContext.required(), page, size, search, entityType, actorId, from, to,
            CorrelationIdContext.current()
        );
    }
}
