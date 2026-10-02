package com.storagehub.api.reservation;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.BookingDocumentService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/reservations/{reservationId}/booking-document")
@RequiredArgsConstructor
public class CustomerBookingDocumentController {

    private final ActorContext actorContext;
    private final BookingDocumentService bookingDocumentService;

    @PostMapping
    public ApiResponse<BookingDocumentResponse> generate(@PathVariable UUID reservationId) {
        return new ApiResponse<>(
            bookingDocumentService.generate(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

    @GetMapping
    public ApiResponse<BookingDocumentResponse> get(@PathVariable UUID reservationId) {
        return new ApiResponse<>(
            bookingDocumentService.get(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable UUID reservationId) {
        BookingDocumentService.DownloadedDocument document =
            bookingDocumentService.download(actorContext.required(), reservationId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(document.contentType()))
            .contentLength(document.sizeBytes())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(document.fileName(), StandardCharsets.UTF_8).build().toString()
            )
            .body(document.resource());
    }
}
