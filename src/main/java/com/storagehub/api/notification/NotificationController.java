package com.storagehub.api.notification;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.NotificationService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final ActorContext actorContext;
    private final NotificationService notificationService;

    @GetMapping
    public PageResponse<NotificationResponse> list(
        @RequestParam(required = false) Boolean isRead,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return notificationService.list(
            actorContext.required(), isRead, page, size, CorrelationIdContext.current()
        );
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationResponse> markRead(@PathVariable UUID id) {
        return new ApiResponse<>(
            notificationService.markRead(actorContext.required(), id),
            CorrelationIdContext.current()
        );
    }

    @PatchMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        return new ApiResponse<>(
            Map.of("updated", notificationService.markAllRead(actorContext.required())),
            CorrelationIdContext.current()
        );
    }
}
