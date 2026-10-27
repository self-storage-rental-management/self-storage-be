package com.storagehub.api.reservation;

import com.storagehub.service.GoodsCatalogEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class GoodsCatalogEventsController {
    private final GoodsCatalogEvents events;

    @GetMapping(value = "/api/goods-catalog/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return events.subscribe();
    }
}
