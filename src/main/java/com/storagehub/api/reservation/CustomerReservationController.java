package com.storagehub.api.reservation;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.CustomerReservationService;
import com.storagehub.service.ReservationCompatibilityService;
import com.storagehub.service.ReservationEmailVerificationService;
import com.storagehub.service.SimulatedPaymentService;
import com.storagehub.api.payment.SimulatedPaymentResponse;
import com.storagehub.api.payment.CreatePaymentComplaintRequest;
import com.storagehub.api.payment.PaymentComplaintResponse;
import com.storagehub.api.file.FileAssetResponse;
import com.storagehub.api.reservation.CheckInAppointmentRequest;
import com.storagehub.service.PaymentComplaintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/reservations")
@RequiredArgsConstructor
public class CustomerReservationController {

    private final ActorContext actorContext;
    private final ReservationCompatibilityService compatibilityService;
    private final com.storagehub.service.ReservationQuoteService quoteService;
    private final com.storagehub.service.ReservationCreationService creationService;
    private final CustomerReservationService customerReservationService;
    private final ReservationEmailVerificationService emailVerificationService;
    private final SimulatedPaymentService simulatedPaymentService;
    private final PaymentComplaintService paymentComplaintService;

    @PostMapping("/compatibility-check")
    public ApiResponse<CompatibilityCheckResponse> checkCompatibility(
        @Valid @RequestBody CompatibilityCheckRequest request
    ) {
        return new ApiResponse<>(
            compatibilityService.check(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/quote")
    public ApiResponse<ReservationQuoteResponse> createQuote(
        @Valid @RequestBody ReservationQuoteRequest request
    ) {
        return new ApiResponse<>(
            quoteService.createQuote(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/rental-packages")
    public ApiResponse<List<ReservationRentalPackageResponse>> listRentalPackages(
        @RequestParam UUID facilityId,
        @RequestParam(required = false) LocalDate startDate
    ) {
        return new ApiResponse<>(
            quoteService.listAvailablePackages(actorContext.required(), facilityId, startDate),
            CorrelationIdContext.current()
        );
    }

    @PostMapping
    public ApiResponse<ReservationResponse> createReservation(
        @Valid @RequestBody CreateReservationRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return new ApiResponse<>(
            creationService.create(actorContext.required(), request, idempotencyKey),
            CorrelationIdContext.current()
        );
    }

    @GetMapping
    public PageResponse<ReservationResponse> listReservations(
        @RequestParam(required = false) ReservationStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return customerReservationService.list(
            actorContext.required(), status, page, size, CorrelationIdContext.current()
        );
    }

    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationDetailResponse> getReservation(
        @PathVariable UUID reservationId
    ) {
        return new ApiResponse<>(
            customerReservationService.getDetail(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/check-in-appointment")
    public ApiResponse<ReservationResponse> setCheckInAppointment(
        @PathVariable UUID reservationId,
        @Valid @RequestBody CheckInAppointmentRequest request
    ) {
        return new ApiResponse<>(
            customerReservationService.setCheckInAppointment(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/{reservationId}/check-in-documents")
    public ApiResponse<List<FileAssetResponse>> listCheckInDocuments(
        @PathVariable UUID reservationId
    ) {
        return new ApiResponse<>(
            customerReservationService.listCheckInDocuments(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/cancel")
    public ApiResponse<ReservationDetailResponse> cancelReservation(
        @PathVariable UUID reservationId,
        @Valid @RequestBody CancelReservationRequest request
    ) {
        return new ApiResponse<>(
            customerReservationService.cancel(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/email-verification")
    public ApiResponse<ReservationEmailVerificationResponse> verifyReservationEmail(
        @PathVariable UUID reservationId,
        @Valid @RequestBody ReservationEmailVerificationRequest request
    ) {
        return new ApiResponse<>(
            emailVerificationService.verify(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/email-verification/resend")
    public ApiResponse<ReservationEmailVerificationResponse> resendReservationEmailCode(
        @PathVariable UUID reservationId
    ) {
        return new ApiResponse<>(
            emailVerificationService.resend(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/{reservationId}/payment")
    public ApiResponse<SimulatedPaymentResponse> getPayment(
        @PathVariable UUID reservationId
    ) {
        return new ApiResponse<>(
            simulatedPaymentService.getPayment(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/simulated-payment")
    public ApiResponse<SimulatedPaymentResponse> simulatePayment(
        @PathVariable UUID reservationId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return new ApiResponse<>(
            simulatedPaymentService.pay(actorContext.required(), reservationId, idempotencyKey),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/payment-complaints")
    public ApiResponse<PaymentComplaintResponse> submitPaymentComplaint(
        @PathVariable UUID reservationId,
        @Valid @RequestBody CreatePaymentComplaintRequest request
    ) {
        return new ApiResponse<>(
            paymentComplaintService.submit(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/{reservationId}/payment-complaint")
    public ApiResponse<PaymentComplaintResponse> getPaymentComplaint(
        @PathVariable UUID reservationId
    ) {
        return new ApiResponse<>(
            paymentComplaintService.getForCustomer(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

}
