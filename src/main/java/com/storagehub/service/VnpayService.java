package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.payment.VnpayPaymentUrlResponse;
import com.storagehub.api.payment.RenewalVnpayPaymentResponse;
import com.storagehub.api.payment.VnpayRefundRequest;
import com.storagehub.api.payment.VnpayTransactionResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.PaymentComplaint;
import com.storagehub.domain.model.PaymentComplaintStatus;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.Renewal;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.PaymentComplaintRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.renewal.RenewalWorkflowService;
import com.storagehub.service.ledger.RenewalVnpaySettlementService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_=@Autowired)
public class VnpayService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.storagehub.service.ledger.RenewalInstallmentService> installments;

    @Transactional
    public RenewalVnpayPaymentResponse createInstallmentPaymentUrl(ActorPrincipal actor,UUID renewalId,UUID quoteId,String key,String ip){
        requireConfigured();requireRenewalSandbox();
        if(renewalSettlement==null||renewalSettlement.getIfAvailable()==null)throw ApiExceptions.conflict("Chưa bật đối soát thanh toán gia hạn.");
        var source=installments.getIfAvailable();if(source==null)throw ApiExceptions.conflict("Chưa bật thanh toán theo kỳ.");
        var payment=source.prepareIntent(actor,renewalId,quoteId,key);
        if(java.util.Set.of(PaymentStatus.PAID,PaymentStatus.FAILED,PaymentStatus.CANCELLED).contains(payment.getStatus()))return renewalPaymentResponse(payment,renewalPaymentMessage(payment),null);
        return renewalPaymentUrl(payment,ip);
    }
    @Transactional(readOnly=true)
    public RenewalVnpayPaymentResponse installmentPayment(ActorPrincipal actor,UUID renewalId,UUID paymentId,String audience){
        var source=installments.getIfAvailable();if(source==null)throw ApiExceptions.conflict("Chưa bật thanh toán theo kỳ.");
        var p=source.read(actor,renewalId,paymentId,audience);return renewalPaymentResponse(p,renewalPaymentMessage(p),null);
    }

    private static final String VERSION = "2.1.0";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE);

    private final VnpayProperties properties;
    private final PaymentRepository paymentRepository;
    private final PaymentComplaintRepository complaintRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final RenewalWorkflowService renewalWorkflowService;
    private final ObjectProvider<RenewalVnpaySettlementService> renewalSettlement;

    @Autowired(required=false)
    private FacilityScopeService facilityScopeService;

    @Autowired(required=false)
    private org.springframework.context.ApplicationEventPublisher paymentEvents;

    /** Backward-compatible constructor for reservation-only unit fixtures. */
    public VnpayService(VnpayProperties properties,PaymentRepository paymentRepository,
        PaymentComplaintRepository complaintRepository,ReservationRepository reservationRepository,
        ReservationPricingSnapshotRepository snapshotRepository,UserRepository userRepository,
        AdminAuthorizationService authorizationService,AuditLogService auditLogService,
        NotificationService notificationService,ObjectMapper objectMapper) {
        this(properties,paymentRepository,complaintRepository,reservationRepository,snapshotRepository,userRepository,
            authorizationService,null,auditLogService,notificationService,objectMapper,null,null);
    }

    /** Backward-compatible constructor for renewal payment unit fixtures. */
    public VnpayService(VnpayProperties properties,PaymentRepository paymentRepository,
        PaymentComplaintRepository complaintRepository,ReservationRepository reservationRepository,
        ReservationPricingSnapshotRepository snapshotRepository,UserRepository userRepository,
        AdminAuthorizationService authorizationService,AuditLogService auditLogService,
        NotificationService notificationService,ObjectMapper objectMapper,
        RenewalWorkflowService renewalWorkflowService,
        ObjectProvider<RenewalVnpaySettlementService> renewalSettlement) {
        this(properties,paymentRepository,complaintRepository,reservationRepository,snapshotRepository,userRepository,
            authorizationService,null,auditLogService,notificationService,objectMapper,renewalWorkflowService,renewalSettlement);
    }


    @Transactional
    public VnpayPaymentUrlResponse createPaymentUrl(
        ActorPrincipal actor,
        UUID reservationId,
        String idempotencyKey,
        String clientIp
    ) {
        requireConfigured();
        String key = requiredKey(idempotencyKey);
        Payment existing = paymentRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            if (existing.getReservation()==null||!existing.getInitiatedBy().getId().equals(actor.userId())
                || !existing.getReservation().getId().equals(reservationId)) {
                throw ApiExceptions.conflict("Idempotency-Key is already used for another payment");
            }
            requirePayable(existing.getReservation());
            return paymentUrl(existing, clientIp);
        }

        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        requirePayable(reservation);
        Payment latestPayment = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservationId, PaymentType.RESERVATION_DEPOSIT)
            .orElse(null);
        Payment alreadyPaid = latestPayment != null && latestPayment.getStatus() == PaymentStatus.PAID
            ? latestPayment : null;
        if (alreadyPaid != null) {
            throw ApiExceptions.conflict("Reservation deposit is already paid");
        }
        if (latestPayment != null && latestPayment.getStatus() == PaymentStatus.PENDING) {
            if (latestPayment.getInitiatedBy() == null
                || !latestPayment.getInitiatedBy().getId().equals(actor.userId())) {
                throw ApiExceptions.conflict("Reservation already has a pending payment");
            }
            return paymentUrl(latestPayment, clientIp);
        }

        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        User initiator = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Payment payment = new Payment();
        payment.setInitiatedBy(initiator);
        payment.setReservation(reservation);
        payment.setAmount(snapshot.getReservationDepositAmount());
        payment.setCurrency("VND");
        payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setIdempotencyKey(key);
        payment.setGatewayIntentId("SH" + UUID.randomUUID().toString().replace("-", ""));
        payment.setGatewayProvider("VNPAY");
        Payment saved = paymentRepository.saveAndFlush(payment);
        auditLogService.recordMutation(
            initiator, "VNPAY_PAYMENT_CREATED", "Payment", saved.getId(),
            reservation.getFacility().getId(), null,
            Map.of("gateway", "VNPAY", "transactionReference", saved.getGatewayIntentId(), "amount", saved.getAmount())
        );
        return paymentUrl(saved, clientIp);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public RenewalVnpayPaymentResponse createRenewalPaymentUrl(
        ActorPrincipal actor, UUID renewalId, String idempotencyKey, String clientIp
    ) {
        requireConfigured();
        requireRenewalSandbox();
        String key=requiredKey(idempotencyKey);
        Payment existing=paymentRepository.findByIdempotencyKey(key).orElse(null);
        if(existing!=null) {
            if(existing.getRenewal()==null||!existing.getRenewal().getId().equals(renewalId)
                ||!existing.getInitiatedBy().getId().equals(actor.userId()))
                throw ApiExceptions.conflict("Idempotency-Key is already used for another payment");
            if(existing.getStatus()==PaymentStatus.PAID||existing.getStatus()==PaymentStatus.FAILED
                ||existing.getStatus()==PaymentStatus.CANCELLED)
                return renewalPaymentResponse(existing,renewalPaymentMessage(existing),null);
            var prepared=renewalWorkflowService.prepareVnpayPayment(actor,renewalId);
            if(existing.getRenewalRevision()==null||!existing.getRenewalRevision().getId().equals(prepared.revision().getId()))
                throw ApiExceptions.conflict("Renewal payment intent is bound to an earlier accepted revision");
            requireRenewalIntentBinding(existing,prepared);
            return renewalPaymentUrl(existing,clientIp);
        }

        var prepared=renewalWorkflowService.prepareVnpayPayment(actor,renewalId);
        var attempts=paymentRepository.findAllByRenewal_IdAndStatusIn(renewalId,
            java.util.List.of(PaymentStatus.PENDING,PaymentStatus.PROCESSING,PaymentStatus.NOT_RECEIVED,PaymentStatus.PAID));
        if(!attempts.isEmpty()) {
            if(attempts.stream().anyMatch(value->value.getStatus()==PaymentStatus.PAID))
                throw ApiExceptions.conflict("Renewal initial rental payment has already been received");
            if(attempts.size()==1&&attempts.getFirst().getRenewalRevision()!=null
                &&attempts.getFirst().getRenewalRevision().getId().equals(prepared.revision().getId())
                &&attempts.getFirst().getInitiatedBy().getId().equals(actor.userId())) {
                requireRenewalIntentBinding(attempts.getFirst(),prepared);
                return renewalPaymentUrl(attempts.getFirst(),clientIp);
            }
            throw ApiExceptions.conflict("An unresolved VNPay Renewal intent already exists; retry it with its original idempotency key");
        }
        User initiator=userRepository.findById(actor.userId())
            .orElseThrow(()->ApiExceptions.unauthorized("The actor no longer exists"));
        Payment payment=new Payment();
        payment.setInitiatedBy(initiator);
        payment.setRenewal(prepared.renewal());
        payment.setRenewalRevision(prepared.revision());
        payment.setPaymentDeadline(prepared.paymentDeadline());
        payment.setAmount(renewalInitialAmount(prepared.terms()));
        payment.setCurrency(prepared.terms().currency());
        payment.setPurpose(PaymentType.RENEWAL);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setIdempotencyKey(key);
        payment.setGatewayIntentId("SH"+UUID.randomUUID().toString().replace("-",""));
        payment.setGatewayProvider("VNPAY");
        Payment saved=paymentRepository.saveAndFlush(payment);
        auditLogService.recordMutation(initiator,"VNPAY_RENEWAL_PAYMENT_CREATED","Payment",saved.getId(),
            prepared.renewal().getRental().getFacility().getId(),null,
            Map.of("gateway","VNPAY","transactionReference",saved.getGatewayIntentId(),"amount",saved.getAmount(),
                "renewalId",renewalId,"acceptedRevisionId",prepared.revision().getId(),"purpose",PaymentType.RENEWAL,
                "paymentDeadline",prepared.paymentDeadline()));
        return renewalPaymentUrl(saved,clientIp);
    }

    @Transactional(readOnly=true)
    public RenewalVnpayPaymentResponse getRenewalPayment(ActorPrincipal actor,UUID renewalId) {
        Payment payment=paymentRepository.findTopByRenewal_IdAndPurposeOrderByCreatedAtDesc(renewalId,PaymentType.RENEWAL)
            .orElseThrow(()->ApiExceptions.notFound("Renewal payment was not found"));
        Renewal renewal=payment.getRenewal();
        if(renewal==null||renewal.getRental()==null||renewal.getRental().getCustomer()==null
            ||!renewal.getRental().getCustomer().getId().equals(actor.userId()))
            throw ApiExceptions.notFound("Renewal payment was not found");
        return renewalPaymentResponse(payment,renewalPaymentMessage(payment),null);
    }

    @Transactional
    public CallbackResult processCallback(Map<String, String> query) {
        requireConfigured();
        if (!verify(query)) {
            return new CallbackResult("97", "Invalid checksum", null, null, false);
        }
        String reference = query.get("vnp_TxnRef");
        Payment payment = paymentRepository.findByGatewayIntentIdForUpdate(reference).orElse(null);
        if (payment == null) {
            return new CallbackResult("01", "Order not found", null, null, false);
        }
        UUID reservationId=payment.getReservation()==null?null:payment.getReservation().getId();
        UUID renewalId=payment.getRenewal()==null?null:payment.getRenewal().getId();
        boolean renewalPayment=renewalId!=null;
        if (!properties.getTmnCode().equals(query.get("vnp_TmnCode")))
            return new CallbackResult("97", "Invalid merchant", payment.getId(), null, renewalId, false, false);
        long receivedAmount;
        try {
            receivedAmount = Long.parseLong(query.getOrDefault("vnp_Amount", "-1"));
        } catch (NumberFormatException exception) {
            return new CallbackResult("04", "Invalid amount", payment.getId(), reservationId, renewalId, false, false);
        }
        if (receivedAmount != amountMinor(payment.getAmount())) {
            return new CallbackResult("04", "Invalid amount", payment.getId(), reservationId, renewalId, false, false);
        }
        String responseCode = query.getOrDefault("vnp_ResponseCode", "99");
        String transactionStatus = query.getOrDefault("vnp_TransactionStatus", responseCode);
        // PAID is terminal for callback metadata, including late funds awaiting reconciliation.
        // Keep the owner's duplicate response contract without letting a signed replay rewrite
        // the accepted transaction or invalidate its financial provenance.
        if (payment.getStatus() == PaymentStatus.PAID) {
            boolean matches = java.util.Objects.equals(responseCode, payment.getGatewayResponseCode())
                && java.util.Objects.equals(transactionStatus, payment.getGatewayTransactionStatus())
                && replayFieldMatches(query.get("vnp_TransactionNo"), payment.getGatewayTransactionNo())
                && replayFieldMatches(query.get("vnp_PayDate"), payment.getGatewayPayDate())
                && replayFieldMatches(query.get("vnp_BankCode"), payment.getGatewayBankCode())
                && replayFieldMatches(query.get("vnp_CardType"), payment.getGatewayCardType());
            if(renewalPayment&&matches&&"RENEWAL_PAYMENT_RECONCILIATION_REQUIRED".equals(payment.getFailureCode())) {
                Instant gatewayPaidAt=parseGatewayPayDate(query.get("vnp_PayDate"));
                if(gatewayPaidAt!=null&&authenticatedRenewalCallback(payment,query,gatewayPaidAt)) {
                    recordVerifiedRenewalPayment(payment,query,gatewayPaidAt);
                }
            }
            paymentRepository.saveAndFlush(payment);
            return new CallbackResult("02", matches ? "Order already confirmed"
                : "Order already confirmed; conflicting callback ignored",
                payment.getId(), reservationId, renewalId,
                !renewalPayment||payment.getFailureCode()==null,
                renewalPayment&&payment.getFailureCode()!=null);
        }
        payment.setGatewayResponseCode(responseCode);
        payment.setGatewayTransactionStatus(transactionStatus);
        setIfPresent(query.get("vnp_TransactionNo"), payment::setGatewayTransactionNo);
        setIfPresent(query.get("vnp_BankCode"), payment::setGatewayBankCode);
        setIfPresent(query.get("vnp_CardType"), payment::setGatewayCardType);
        setIfPresent(query.get("vnp_PayDate"), payment::setGatewayPayDate);
        payment.setProcessedAt(Instant.now());
        boolean success = "00".equals(responseCode) && "00".equals(transactionStatus);
        Reservation reservation = payment.getReservation();
        if (success) {
            Instant gatewayPaidAt = parseGatewayPayDate(payment.getGatewayPayDate());
            if (gatewayPaidAt == null || gatewayPaidAt.isAfter(Instant.now())) {
                paymentRepository.saveAndFlush(payment);
                return new CallbackResult("04", "Invalid payment date", payment.getId(), reservationId, renewalId, false, false);
            }
            if(renewalPayment) {
                if(payment.getPaymentDeadline()==null||gatewayPaidAt.isAfter(payment.getPaymentDeadline())) {
                    markRenewalLatePayment(payment,gatewayPaidAt);
                    recordRenewalGatewayEvidence(payment,query,gatewayPaidAt,false,"PAYMENT_AFTER_DEADLINE");
                    paymentRepository.saveAndFlush(payment);
                    return new CallbackResult("00","Payment received after deadline; reconciliation required",payment.getId(),null,renewalId,false,true);
                }
                payment.setStatus(PaymentStatus.PAID);
                payment.setPaidAt(payment.getPaidAt()==null?gatewayPaidAt:payment.getPaidAt());
                payment.setFailureCode("RENEWAL_PAYMENT_RECONCILIATION_REQUIRED");
                payment.setFailureReason("Authenticated funds require Renewal source and obligation reconciliation");
                paymentRepository.saveAndFlush(payment);
                if(authenticatedRenewalCallback(payment,query,gatewayPaidAt)) {
                    recordVerifiedRenewalPayment(payment,query,gatewayPaidAt);
                } else {
                    recordRenewalGatewayEvidence(payment,query,gatewayPaidAt,false,"CALLBACK_BINDING_UNVERIFIED");
                    payment.setFailureReason("VNPAY callback does not match the configured merchant and transaction proof");
                }
                paymentRepository.saveAndFlush(payment);
                return new CallbackResult("00","Payment received; allocation and signing preparation pending",
                    payment.getId(),null,renewalId,false,true);
            }
            if (gatewayPaidAt.isAfter(paymentDeadline(reservation))) {
                markLatePaymentForReconciliation(payment, gatewayPaidAt);
                expireGraceReservationAfterFinalFailure(payment, reservation);
                paymentRepository.saveAndFlush(payment);
                return new CallbackResult("00", "Payment received after deadline", payment.getId(), reservationId, null, false, false);
            }
            markPaid(payment, gatewayPaidAt);
        } else {
            PaymentStatus previousStatus = payment.getStatus();
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode(responseCode);
            payment.setFailureReason("VNPAY transaction was not successful");
            if (previousStatus != PaymentStatus.FAILED && reservation != null) {
                notifyPaymentFailure(payment);
            }
            if(reservation!=null)expireGraceReservationAfterFinalFailure(payment, reservation);
        }
        paymentRepository.saveAndFlush(payment);
        boolean reservationConfirmed = success && reservation.getStatus() == ReservationStatus.CONFIRMED;
        // An IPN acknowledgement is not accepted Booking credit. Late funds remain unreconciled.
        // Replays and query/complaint transitions do not create callback proof.
        if (reservationConfirmed && payment.getStatus() == PaymentStatus.PAID
            && payment.getPurpose() == PaymentType.RESERVATION_DEPOSIT
            && !"PAYMENT_AFTER_DEADLINE".equals(payment.getFailureCode())
            && "VNPAY".equals(payment.getGatewayProvider())
            && properties.getTmnCode().equals(query.get("vnp_TmnCode"))
            && query.get("vnp_PayDate") != null
            && payment.getPaidAt().equals(parseGatewayPayDate(query.get("vnp_PayDate")))
            && java.util.Objects.equals(query.get("vnp_TransactionNo"), payment.getGatewayTransactionNo())
            && payment.getGatewayTransactionNo()!=null && !payment.getGatewayTransactionNo().isBlank()) {
            var proof=new java.util.LinkedHashMap<String,Object>();
            proof.put("paymentId",payment.getId());proof.put("reservationId",reservation.getId());
            proof.put("customerId",reservation.getCustomer().getId());proof.put("intentId",payment.getGatewayIntentId());
            proof.put("transactionNo",payment.getGatewayTransactionNo());proof.put("purpose",payment.getPurpose());
            proof.put("currency",payment.getCurrency());proof.put("amount",payment.getAmount());proof.put("signatureVerified",true);
            proof.put("bookingPaymentAccepted",true);
            proof.put("gatewayPaidAt",parseGatewayPayDate(payment.getGatewayPayDate()).toString());
            proof.put("paymentDeadline",paymentDeadline(reservation).toString());
            proof.put("environment",PaymentProvenance.environment(properties.getPayUrl(),properties.getApiUrl()));
            auditLogService.recordMutation((User)null,PaymentProvenance.CALLBACK_ACTION,"Payment",payment.getId(),reservation.getFacility().getId(),null,proof);
        }
        return new CallbackResult("00", "Confirm Success", payment.getId(), reservationId, null, reservationConfirmed, false);
    }

    @Transactional
    public VnpayTransactionResponse query(ActorPrincipal actor, UUID paymentId, String clientIp) {
        requireConfigured();
        authorizationService.require(actor, SystemPermission.VIEW_PAYMENTS);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment was not found"));
        if(payment.getRenewal()!=null&&payment.getStatus()==PaymentStatus.PAID)
            return response(payment,"Verified Renewal payment is immutable; allocation retries use its stored evidence");
        Map<String, String> request = queryRequest(payment, clientIp);
        Map<String, String> response = post(request);
        if (!verifyQueryResponse(response)
            || !payment.getGatewayIntentId().equals(response.get("vnp_TxnRef"))
            || (payment.getRenewal()!=null&&!properties.getTmnCode().equals(response.get("vnp_TmnCode")))) {
            throw ApiExceptions.conflict("VNPAY returned an invalid query response");
        }
        payment.setLastReconciledAt(Instant.now());
        payment.setGatewayResponseCode(response.get("vnp_ResponseCode"));
        payment.setGatewayTransactionStatus(response.get("vnp_TransactionStatus"));
        if (response.get("vnp_TransactionNo") != null) payment.setGatewayTransactionNo(response.get("vnp_TransactionNo"));
        if (response.get("vnp_PayDate") != null) payment.setGatewayPayDate(response.get("vnp_PayDate"));
        if ("00".equals(response.get("vnp_ResponseCode"))
            && "00".equals(response.get("vnp_TransactionStatus"))
            && payment.getStatus() != PaymentStatus.PAID) {
            validateQueryAmount(payment, response);
            Instant gatewayPaidAt = parseGatewayPayDate(response.get("vnp_PayDate"));
            if (gatewayPaidAt == null) {
                throw ApiExceptions.conflict("VNPAY returned an invalid payment date");
            }
            if(payment.getRenewal()!=null) {
                if(payment.getPaymentDeadline()==null||gatewayPaidAt.isAfter(payment.getPaymentDeadline())) {
                    markRenewalLatePayment(payment,gatewayPaidAt);
                } else {
                    payment.setStatus(PaymentStatus.PAID);payment.setPaidAt(gatewayPaidAt);payment.setProcessedAt(Instant.now());
                    payment.setFailureCode("RENEWAL_PAYMENT_RECONCILIATION_REQUIRED");
                    payment.setFailureReason("Verified funds await Ledger allocation and signing preparation");
                    if(authenticatedRenewalCallback(payment,response,gatewayPaidAt))recordVerifiedRenewalPayment(payment,response,gatewayPaidAt);
                }
            } else if (gatewayPaidAt.isAfter(paymentDeadline(payment.getReservation()))) {
                markLatePaymentForReconciliation(payment, gatewayPaidAt);
                expireGraceReservationAfterFinalFailure(payment, payment.getReservation());
            } else {
                markPaid(payment, gatewayPaidAt);
            }
        } else if (("91".equals(response.get("vnp_ResponseCode"))
            || ("00".equals(response.get("vnp_ResponseCode"))
                && "02".equals(response.get("vnp_TransactionStatus"))))
            && payment.getStatus() != PaymentStatus.PAID) {
            markQueryFailure(payment, response.getOrDefault("vnp_TransactionStatus", "91"),
                "VNPAY confirmed that the transaction was not successful");
            if(payment.getReservation()!=null)expireGraceReservationAfterFinalFailure(payment, payment.getReservation());
        }
        paymentRepository.saveAndFlush(payment);
        return response(payment, response.getOrDefault("vnp_Message", "Query completed"));
    }

    ReconciliationOutcome reconcileAtPaymentDeadline(Payment payment) {
        if (!properties.isEnabled() || properties.getTmnCode().isBlank() || properties.getHashSecret().isBlank()) {
            return ReconciliationOutcome.UNKNOWN;
        }
        // VNPay rejects repeated QueryDr requests for the same transaction
        // within five minutes (94). Polling every scheduler tick prevents recovery.
        if(payment.getRenewal()!=null && payment.getLastReconciledAt()!=null
            && payment.getLastReconciledAt().plusSeconds(300).isAfter(Instant.now()))return ReconciliationOutcome.UNKNOWN;
        if(payment.getRenewal()!=null)payment.setLastReconciledAt(Instant.now());
        final Map<String, String> gateway;
        try {
            gateway = post(queryRequest(payment, "127.0.0.1"));
        } catch (RuntimeException exception) {
            return ReconciliationOutcome.UNKNOWN;
        }
        payment.setLastReconciledAt(Instant.now());
        if (!verifyQueryResponse(gateway)
            || !payment.getGatewayIntentId().equals(gateway.get("vnp_TxnRef"))
            || (payment.getRenewal()!=null&&!properties.getTmnCode().equals(gateway.get("vnp_TmnCode")))) {
            return ReconciliationOutcome.UNKNOWN;
        }
        payment.setGatewayResponseCode(gateway.get("vnp_ResponseCode"));
        payment.setGatewayTransactionStatus(gateway.get("vnp_TransactionStatus"));
        if (gateway.get("vnp_TransactionNo") != null) payment.setGatewayTransactionNo(gateway.get("vnp_TransactionNo"));
        if (gateway.get("vnp_PayDate") != null) payment.setGatewayPayDate(gateway.get("vnp_PayDate"));

        String responseCode = gateway.get("vnp_ResponseCode");
        if ("91".equals(responseCode)) {
            if(payment.getRenewal()!=null && (payment.getPaymentDeadline()==null
                || Instant.now().isBefore(payment.getPaymentDeadline().plusSeconds(300))
                || payment.getStatus()==PaymentStatus.PAID || payment.getPaidAt()!=null
                || payment.getRenewalEvidenceRef()!=null || payment.getRenewalLedgerReceiptRef()!=null
                || payment.getGatewayTransactionNo()!=null && !payment.getGatewayTransactionNo().isBlank()
                    && !"0".equals(payment.getGatewayTransactionNo())))return ReconciliationOutcome.UNKNOWN;
            markQueryFailure(payment, "91", "VNPAY could not find the transaction at the payment deadline");
            return ReconciliationOutcome.FINAL_FAILURE;
        }
        if (!"00".equals(responseCode)) return ReconciliationOutcome.UNKNOWN;

        try {
            validateQueryAmount(payment, gateway);
        } catch (RuntimeException exception) {
            return ReconciliationOutcome.UNKNOWN;
        }
        String transactionStatus = gateway.get("vnp_TransactionStatus");
        if ("00".equals(transactionStatus)) {
            Instant gatewayPaidAt = parseGatewayPayDate(gateway.get("vnp_PayDate"));
            if (gatewayPaidAt == null) return ReconciliationOutcome.UNKNOWN;
            if(payment.getRenewal()!=null) {
                if(payment.getPaymentDeadline()==null||gatewayPaidAt.isAfter(payment.getPaymentDeadline()))
                    markRenewalLatePayment(payment,gatewayPaidAt);
                else {
                    payment.setStatus(PaymentStatus.PAID);payment.setPaidAt(gatewayPaidAt);payment.setProcessedAt(Instant.now());
                    payment.setFailureCode("RENEWAL_PAYMENT_RECONCILIATION_REQUIRED");
                    payment.setFailureReason("Verified funds await Ledger allocation and signing preparation");
                    if(authenticatedRenewalCallback(payment,gateway,gatewayPaidAt))recordVerifiedRenewalPayment(payment,gateway,gatewayPaidAt);
                }
                return ReconciliationOutcome.UNKNOWN;
            }
            if (gatewayPaidAt.isAfter(paymentDeadline(payment.getReservation()))) {
                markLatePaymentForReconciliation(payment, gatewayPaidAt);
                return ReconciliationOutcome.FINAL_FAILURE;
            }
            markPaid(payment, gatewayPaidAt);
            return ReconciliationOutcome.SUCCESS;
        }
        if ("02".equals(transactionStatus) || payment.getRenewal()!=null && "08".equals(transactionStatus)) {
            if(payment.getRenewal()!=null && (payment.getStatus()==PaymentStatus.PAID || payment.getPaidAt()!=null
                || payment.getRenewalEvidenceRef()!=null || payment.getRenewalLedgerReceiptRef()!=null
                || gateway.get("vnp_Amount")==null
                || "08".equals(transactionStatus) && (payment.getPaymentDeadline()==null
                    || payment.getPaymentDeadline().isAfter(Instant.now()))))return ReconciliationOutcome.UNKNOWN;
            markQueryFailure(payment, transactionStatus, "08".equals(transactionStatus)
                ? "VNPAY confirmed that the transaction timed out without successful payment"
                : "VNPAY confirmed that the transaction failed");
            return ReconciliationOutcome.FINAL_FAILURE;
        }
        return ReconciliationOutcome.UNKNOWN;
    }

    @Transactional
    public VnpayTransactionResponse refund(
        ActorPrincipal actor,
        UUID paymentId,
        VnpayRefundRequest request,
        String clientIp
    ) {
        requireConfigured();
        if (actor == null || !actor.hasAnyRole(RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Business Operations execution is required for VNPAY refunds");
        }
        authorizationService.require(actor, SystemPermission.EXECUTE_REFUNDS);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment was not found"));
        if (payment.getStatus() != PaymentStatus.PAID || payment.getGatewayTransactionNo() == null) {
            throw ApiExceptions.conflict("Only a successful VNPAY payment can be refunded");
        }
        UUID facilityId = payment.getReservation().getFacility().getId();
        if (facilityScopeService != null && actor.hasAnyRole(RoleCode.MANAGER, RoleCode.BUSINESS)) {
            facilityScopeService.assertCanManage(actor, facilityId);
        }
        if(payment.getRenewal()!=null)
            throw ApiExceptions.conflict("Renewal refunds require the Renewal refund entitlement and payout workflow");
        BigDecimal remaining = payment.getAmount().subtract(payment.getRefundedAmount());
        if (request.amount().compareTo(remaining) > 0) {
            throw ApiExceptions.validation("Refund amount exceeds the refundable amount", null);
        }
        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Map<String, String> payload = refundRequest(payment, request, user.getEmail(), clientIp);
        Map<String, String> gateway = post(payload);
        if ("00".equals(gateway.get("vnp_ResponseCode"))) {
            payment.setRefundedAmount(payment.getRefundedAmount().add(request.amount()));
        }
        payment.setLastReconciledAt(Instant.now());
        paymentRepository.saveAndFlush(payment);
        auditLogService.recordMutation(
            user, "VNPAY_REFUND_REQUESTED", "Payment", payment.getId(),
            payment.getReservation().getFacility().getId(), null,
            Map.of("amount", request.amount(), "reason", request.reason(),
                "responseCode", gateway.getOrDefault("vnp_ResponseCode", "99"))
        );
        return response(payment, gateway.getOrDefault("vnp_Message", "Refund request completed"));
    }

    @Transactional(readOnly = true)
    public VnpayTransactionResponse get(ActorPrincipal actor, UUID reservationId) {
        Payment payment = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservationId, PaymentType.RESERVATION_DEPOSIT)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation payment was not found"));
        if (!payment.getReservation().getCustomer().getId().equals(actor.userId())) {
            throw ApiExceptions.notFound("Reservation payment was not found");
        }
        return response(payment, message(payment));
    }

    private VnpayPaymentUrlResponse paymentUrl(Payment payment, String clientIp) {
        Instant deadline = paymentDeadline(payment.getReservation());
        if (!deadline.isAfter(Instant.now())) {
            throw ApiExceptions.conflict("Reservation payment deadline has expired");
        }
        String url = gatewayPaymentUrl(payment, deadline,
            "Thanh toan coc don " + payment.getReservation().getReservationCode(), clientIp);
        return new VnpayPaymentUrlResponse(
            payment.getId(), payment.getReservation().getId(), payment.getGatewayIntentId(),
            payment.getAmount(), payment.getCurrency(), url
        );
    }

    private RenewalVnpayPaymentResponse renewalPaymentUrl(Payment payment, String clientIp) {
        Instant deadline = payment.getPaymentDeadline();
        if (payment.getRenewal() == null || payment.getRenewalRevision() == null
            || deadline == null || !deadline.isAfter(Instant.now())) {
            throw ApiExceptions.conflict("Renewal payment intent has expired or is missing its resource binding");
        }
        String url = gatewayPaymentUrl(payment, deadline,
            "Thanh toan gia han " + payment.getRenewal().getId(), clientIp);
        return renewalPaymentResponse(payment, "VNPay Sandbox payment intent is ready", url);
    }

    /** Reservation and Renewal use the same merchant, signing and gateway return endpoint. */
    private String gatewayPaymentUrl(Payment payment, Instant deadline, String orderInfo, String clientIp) {
        Instant now = Instant.now();
        Instant gatewayExpiry = now.plus(Duration.ofMinutes(15));
        if (gatewayExpiry.isAfter(deadline)) {
            gatewayExpiry = deadline;
        }
        Map<String, String> values = new TreeMap<>();
        values.put("vnp_Version", VERSION);
        values.put("vnp_Command", "pay");
        values.put("vnp_TmnCode", properties.getTmnCode());
        values.put("vnp_Amount", String.valueOf(amountMinor(payment.getAmount())));
        values.put("vnp_CurrCode", "VND");
        values.put("vnp_TxnRef", payment.getGatewayIntentId());
        values.put("vnp_OrderInfo", orderInfo);
        values.put("vnp_OrderType", "other");
        values.put("vnp_Locale", "vn");
        values.put("vnp_ReturnUrl", properties.getReturnUrl());
        values.put("vnp_IpAddr", ip(clientIp));
        values.put("vnp_CreateDate", DATE_TIME.format(now));
        values.put("vnp_ExpireDate", DATE_TIME.format(gatewayExpiry));
        String query = encode(values);
        return properties.getPayUrl() + "?" + query + "&vnp_SecureHash=" + hmac(query);
    }

    private static java.math.BigDecimal renewalInitialAmount(com.storagehub.api.renewal.RenewalQuoteResponse.Terms terms) {
        return terms.renewalDepositAmount().signum()>0?terms.renewalDepositAmount():terms.totalAfterDiscount();
    }
    private void requireRenewalIntentBinding(Payment payment,RenewalWorkflowService.PreparedPayment prepared) {
        if(payment.getPurpose()!=PaymentType.RENEWAL||!"VNPAY".equals(payment.getGatewayProvider())
            ||payment.getAmount()==null||payment.getAmount().compareTo(renewalInitialAmount(prepared.terms()))!=0
            ||!java.util.Objects.equals(payment.getCurrency(),prepared.terms().currency())
            ||payment.getPaymentDeadline()==null||prepared.paymentDeadline()==null
            ||Math.abs(payment.getPaymentDeadline().toEpochMilli()-prepared.paymentDeadline().toEpochMilli())>2000)
            throw ApiExceptions.conflict("Renewal payment amount, purpose, currency or deadline binding changed");
    }

    private RenewalVnpayPaymentResponse renewalPaymentResponse(Payment payment,String message,String url) {
        return new RenewalVnpayPaymentResponse(payment.getId(),payment.getRenewal().getId(),
            payment.getRenewalRevision()==null?null:payment.getRenewalRevision().getId(),payment.getGatewayIntentId(),
            payment.getAmount(),payment.getCurrency(),payment.getStatus(),payment.getPaymentDeadline(),payment.getPaidAt(),
            payment.getStatus()==PaymentStatus.PAID&&(payment.getFailureCode()!=null
                ||"RECONCILIATION_REQUIRED".equals(renewalSettlementStage(payment))),message,url,renewalSettlementStage(payment));
    }

    private String renewalSettlementStage(Payment payment) {
        if(payment.getPurpose()==PaymentType.RENEWAL_INSTALLMENT){
            if(payment.getStatus()!=PaymentStatus.PAID)return "AWAITING_PAYMENT";
            return payment.getRenewalAllocatedAt()!=null?"COMPLETED":payment.getRenewalEvidenceRef()!=null?"VERIFIED_AWAITING_ALLOCATION":"RECONCILIATION_REQUIRED";
        }
        if(payment.getRenewal().getStatus()==com.storagehub.domain.model.RenewalStatus.completed)return "COMPLETED";
        if(payment.getStatus()!=PaymentStatus.PAID)return "AWAITING_PAYMENT";
        if(payment.getRenewalSigningPreparedAt()!=null)return "AWAITING_SIGNING";
        if(payment.getRenewalAllocatedAt()!=null)return "ALLOCATED_AWAITING_SIGNING";
        return payment.getRenewalEvidenceRef()!=null?"VERIFIED_AWAITING_ALLOCATION":"RECONCILIATION_REQUIRED";
    }

    private String renewalPaymentMessage(Payment payment) {
        if(payment.getPurpose()==PaymentType.RENEWAL_INSTALLMENT){
            if(payment.getStatus()==PaymentStatus.FAILED)return "Giao dịch thanh toán kỳ thất bại; có thể lấy báo giá mới để thử lại.";
            if(payment.getStatus()==PaymentStatus.CANCELLED)return "Giao dịch thanh toán kỳ đã hủy.";
            if(payment.getStatus()==PaymentStatus.PAID)return payment.getRenewalAllocatedAt()!=null?"Đã phân bổ tiền vào các kỳ thuê.":"Đã nhận tiền, đang đối soát phân bổ theo kỳ.";
            return "Đang chờ kết quả thanh toán kỳ từ VNPay.";
        }
        if(payment.getFailureCode()!=null)return "Payment received; Renewal reconciliation is required";
        return payment.getStatus()==PaymentStatus.PAID?"Renewal initial rental payment recorded"
            :payment.getStatus()==PaymentStatus.FAILED?"VNPay Renewal payment failed":"Renewal payment is awaiting VNPay confirmation";
    }

    private Map<String, String> queryRequest(Payment payment, String clientIp) {
        String createDate = DATE_TIME.format(Instant.now());
        String transactionDate = payment.getGatewayPayDate() != null
            ? payment.getGatewayPayDate() : DATE_TIME.format(payment.getCreatedAt());
        Map<String, String> values = new HashMap<>();
        values.put("vnp_RequestId", requestId()); values.put("vnp_Version", VERSION);
        values.put("vnp_Command", "querydr"); values.put("vnp_TmnCode", properties.getTmnCode());
        values.put("vnp_TxnRef", payment.getGatewayIntentId()); values.put("vnp_TransactionDate", transactionDate);
        values.put("vnp_CreateDate", createDate); values.put("vnp_IpAddr", ip(clientIp));
        values.put("vnp_OrderInfo", "Doi soat " + payment.getGatewayIntentId());
        values.put("vnp_SecureHash", hmac(join(values, "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TxnRef", "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo")));
        return values;
    }

    private Map<String, String> refundRequest(Payment payment, VnpayRefundRequest request, String createBy, String clientIp) {
        Map<String, String> values = new HashMap<>();
        values.put("vnp_RequestId", requestId()); values.put("vnp_Version", VERSION);
        values.put("vnp_Command", "refund"); values.put("vnp_TmnCode", properties.getTmnCode());
        values.put("vnp_TransactionType", request.amount().compareTo(payment.getAmount()) == 0 ? "02" : "03");
        values.put("vnp_TxnRef", payment.getGatewayIntentId());
        values.put("vnp_Amount", String.valueOf(amountMinor(request.amount())));
        values.put("vnp_TransactionNo", payment.getGatewayTransactionNo());
        values.put("vnp_TransactionDate", payment.getGatewayPayDate());
        values.put("vnp_CreateBy", createBy.substring(0, Math.min(createBy.length(), 32)));
        values.put("vnp_CreateDate", DATE_TIME.format(Instant.now())); values.put("vnp_IpAddr", ip(clientIp));
        values.put("vnp_OrderInfo", request.reason());
        values.put("vnp_SecureHash", hmac(join(values, "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TransactionType", "vnp_TxnRef", "vnp_Amount", "vnp_TransactionNo", "vnp_TransactionDate", "vnp_CreateBy", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo")));
        return values;
    }

    private Map<String, String> post(Map<String, String> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiUrl()))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiExceptions.conflict("VNPAY API is unavailable");
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiExceptions.conflict("VNPAY API request was interrupted");
        } catch (java.io.IOException exception) {
            throw ApiExceptions.conflict("VNPAY API is unavailable");
        }
    }

    private boolean verify(Map<String, String> query) {
        String supplied = query.get("vnp_SecureHash");
        if (supplied == null) return false;
        Map<String, String> signed = new TreeMap<>();
        query.forEach((key, value) -> {
            if (key.startsWith("vnp_") && !"vnp_SecureHash".equals(key) && !"vnp_SecureHashType".equals(key)
                && value != null && !value.isBlank()) signed.put(key, value);
        });
        return constantTimeEquals(hmac(encode(signed)), supplied);
    }
    private boolean verifyQueryResponse(Map<String, String> response) {
        String supplied = response.get("vnp_SecureHash");
        if (supplied == null || supplied.isBlank()) return false;
        String data = join(response,
            "vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
            "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode",
            "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType",
            "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode",
            "vnp_PromotionAmount");
        return constantTimeEquals(hmac(data), supplied);
    }

    private String encode(Map<String, String> values) {
        return values.entrySet().stream().filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
            .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
            .reduce((left, right) -> left + "&" + right).orElse("");
    }

    private String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String join(Map<String, String> values, String... keys) {
        return java.util.Arrays.stream(keys).map(key -> values.getOrDefault(key, "")).reduce((a, b) -> a + "|" + b).orElse("");
    }
    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(properties.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA512 is unavailable", exception);
        }
    }
    private boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(expected.toLowerCase().getBytes(StandardCharsets.US_ASCII), actual.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
    private long amountMinor(BigDecimal amount) { return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.UNNECESSARY).longValueExact(); }
    private String requestId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 32); }
    private String ip(String value) { return value == null || value.isBlank() ? "127.0.0.1" : value.split(",")[0].trim(); }
    private String requiredKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) throw ApiExceptions.validation("Idempotency-Key is required and must be at most 100 characters", null);
        return value.trim();
    }
    private void requireConfigured() {
        if (!properties.isEnabled() || properties.getTmnCode().isBlank() || properties.getHashSecret().isBlank()) {
            throw ApiExceptions.conflict("VNPAY is not configured");
        }
    }
    private void requireRenewalSandbox() {
        if(!"SANDBOX".equals(PaymentProvenance.environment(properties.getPayUrl(),properties.getApiUrl())))
            throw ApiExceptions.conflict("Renewal payment is currently limited to VNPay Sandbox");
    }
    private void requirePayable(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.AWAITING_PAYMENT) throw ApiExceptions.conflict("Reservation is not awaiting payment");
        Instant deadline = reservation.getPaymentExpiresAt() != null ? reservation.getPaymentExpiresAt() : reservation.getHoldExpiresAt();
        if (deadline == null || !deadline.isAfter(Instant.now())) throw ApiExceptions.conflict("Reservation payment deadline has expired");
    }
    private void markPaid(Payment payment, Instant gatewayPaidAt) {
        PaymentStatus previousStatus = payment.getStatus();
        Instant paidAt = payment.getPaidAt() != null ? payment.getPaidAt() : gatewayPaidAt;
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(paidAt);
        payment.setProcessedAt(Instant.now());
        Reservation reservation = payment.getReservation();
        ReservationStatus previousReservationStatus = reservation.getStatus();
        if (reservation.getStatus() == ReservationStatus.AWAITING_PAYMENT
            || reservation.getStatus() == ReservationStatus.PAYMENT_GRACE
            || reservation.getStatus() == ReservationStatus.PAYMENT_REVIEW) {
            resolveActiveComplaint(reservation, paidAt);
            ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservation.getId())
                .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
            ReservationPaymentTerms.apply(reservation, snapshot.getRentalMonths(), paidAt);
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setDepositPaidAt(paidAt);
            reservation.setConfirmedAt(paidAt);
            reservationRepository.saveAndFlush(reservation);
        }
        if (previousStatus != PaymentStatus.PAID) {
            notificationService.createNotification(
                reservation.getCustomer().getId(), NotificationType.PAYMENT,
                "Thanh toán thành công",
                "VNPay đã xác nhận thanh toán cọc cho đơn "
                    + reservation.getReservationCode() + ".",
                reservation.getId()
            );
        }
        if (previousReservationStatus != ReservationStatus.CONFIRMED
            && reservation.getStatus() == ReservationStatus.CONFIRMED) {
            notificationService.createFacilityOperationsNotifications(
                reservation.getFacility().getId(),
                NotificationType.RESERVATION,
                "Có đơn đã xác nhận chờ phân kho",
                "Đơn " + reservation.getReservationCode()
                    + " đã thanh toán cọc và đang chờ nhân viên phân gian kho.",
                reservation.getId()
            );
        }
    }

    private void notifyPaymentFailure(Payment payment) {
        Reservation reservation = payment.getReservation();
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.PAYMENT,
            "Thanh toán chưa thành công",
            "Giao dịch VNPay của đơn " + reservation.getReservationCode()
                + " chưa thành công. Bạn có thể thử lại nếu đơn vẫn còn thời hạn.",
            reservation.getId()
        );
    }

    private void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) setter.accept(value);
    }
    private boolean authenticatedRenewalCallback(Payment payment,Map<String,String> query,Instant gatewayPaidAt) {
        return payment!=null&&payment.getRenewal()!=null&&payment.getRenewalRevision()!=null
            &&java.util.Set.of(PaymentType.RENEWAL,PaymentType.RENEWAL_INSTALLMENT).contains(payment.getPurpose())&&"VNPAY".equals(payment.getGatewayProvider())
            &&properties.getTmnCode().equals(query.get("vnp_TmnCode"))
            &&"SANDBOX".equals(PaymentProvenance.environment(properties.getPayUrl(),properties.getApiUrl()))
            &&payment.getGatewayIntentId().equals(query.get("vnp_TxnRef"))
            &&"00".equals(query.get("vnp_ResponseCode"))&&"00".equals(query.get("vnp_TransactionStatus"))
            &&query.get("vnp_TransactionNo")!=null&&!query.get("vnp_TransactionNo").isBlank()
            &&query.get("vnp_PayDate")!=null&&gatewayPaidAt.equals(parseGatewayPayDate(query.get("vnp_PayDate")))
            &&!gatewayPaidAt.isAfter(Instant.now())
            &&gatewayPaidAt.equals(payment.getPaidAt())&&payment.getPaymentDeadline()!=null
            &&!gatewayPaidAt.isAfter(payment.getPaymentDeadline());
    }
    /** Transaction A only: no calendar, Ledger or activation work can roll back received funds. */
    private void recordVerifiedRenewalPayment(Payment payment,Map<String,String> query,Instant paidAt) {
        if(payment.getRenewalEvidenceRef()==null) {
            var evidence=auditLogService.recordMutation((User)null,PaymentProvenance.CALLBACK_ACTION,"Payment",payment.getId(),
                payment.getRenewal().getRental().getFacility().getId(),null,renewalCallbackProof(payment,query,paidAt));
            payment.setRenewalEvidenceRef(evidence.getId());
        }
        payment.setRenewalNextRetryAt(Instant.now());
        if(paymentEvents!=null)paymentEvents.publishEvent(new com.storagehub.service.ledger.RenewalPaymentReconciler.Verified(payment.getId()));
    }
    private com.storagehub.domain.model.ActivityLog recordRenewalGatewayEvidence(
        Payment payment,Map<String,String> query,Instant gatewayPaidAt,boolean accepted,String reason
    ) {
        var renewal=payment.getRenewal();var proof=new java.util.LinkedHashMap<String,Object>();
        proof.put("paymentId",payment.getId());proof.put("renewalId",renewal.getId());
        proof.put("acceptedRevisionId",payment.getRenewalRevision()==null?null:payment.getRenewalRevision().getId());
        proof.put("customerId",renewal.getRental().getCustomer().getId());proof.put("intentId",payment.getGatewayIntentId());
        proof.put("transactionNo",payment.getGatewayTransactionNo());proof.put("purpose",payment.getPurpose());
        proof.put("currency",payment.getCurrency());proof.put("amount",payment.getAmount());
        proof.put("signatureVerified",true);proof.put("merchantMatched",properties.getTmnCode().equals(query.get("vnp_TmnCode")));
        proof.put("gatewayPaidAt",gatewayPaidAt==null?null:gatewayPaidAt.toString());
        proof.put("paymentDeadline",payment.getPaymentDeadline()==null?null:payment.getPaymentDeadline().toString());
        proof.put("environment",PaymentProvenance.environment(properties.getPayUrl(),properties.getApiUrl()));
        proof.put("accepted",accepted);if(reason!=null)proof.put("reason",reason);
        return auditLogService.recordMutation((User)null,"VNPAY_RENEWAL_GATEWAY_EVIDENCE_V1","Payment",payment.getId(),
            renewal.getRental().getFacility().getId(),null,proof);
    }
    private Map<String,Object> renewalCallbackProof(Payment payment,Map<String,String> query,Instant gatewayPaidAt) {
        var renewal=payment.getRenewal();var proof=new java.util.LinkedHashMap<String,Object>();
        proof.put("paymentId",payment.getId());proof.put("renewalId",renewal.getId());
        proof.put("acceptedRevisionId",payment.getRenewalRevision().getId());
        proof.put("customerId",renewal.getRental().getCustomer().getId());proof.put("intentId",payment.getGatewayIntentId());
        proof.put("transactionNo",payment.getGatewayTransactionNo());proof.put("purpose",payment.getPurpose());
        proof.put("currency",payment.getCurrency());proof.put("amount",payment.getAmount());
        proof.put("signatureVerified",true);proof.put("renewalPaymentAccepted",true);
        proof.put("gatewayPaidAt",gatewayPaidAt.toString());proof.put("paymentDeadline",payment.getPaymentDeadline().toString());
        proof.put("environment",PaymentProvenance.environment(properties.getPayUrl(),properties.getApiUrl()));
        proof.put("merchantMatched",properties.getTmnCode().equals(query.get("vnp_TmnCode")));
        return proof;
    }
    private void markRenewalLatePayment(Payment payment,Instant gatewayPaidAt) {
        payment.setStatus(PaymentStatus.PAID);payment.setPaidAt(gatewayPaidAt);payment.setProcessedAt(Instant.now());
        payment.setFailureCode("PAYMENT_AFTER_DEADLINE");
        payment.setFailureReason("VNPAY recorded Renewal funds after the persisted payment deadline; manual financial reconciliation is required");
    }
    private boolean replayFieldMatches(String incoming, String recorded) {
        // Missing optional fields were previously no-ops; they must not enrich a PAID row either.
        return incoming == null || incoming.isBlank() || java.util.Objects.equals(incoming, recorded);
    }
    private void resolveActiveComplaint(Reservation reservation, Instant resolvedAt) {
        if (reservation.getStatus() != ReservationStatus.PAYMENT_REVIEW) return;
        PaymentComplaint complaint = complaintRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (complaint == null
            || (complaint.getStatus() != PaymentComplaintStatus.PENDING
                && complaint.getStatus() != PaymentComplaintStatus.REVIEW_OVERDUE)) return;
        complaint.setStatus(PaymentComplaintStatus.APPROVED);
        complaint.setReviewedAt(resolvedAt);
        complaint.setDecisionReason("Automatically resolved by a valid VNPAY confirmation");
        complaintRepository.saveAndFlush(complaint);
    }
    private void markQueryFailure(Payment payment, String code, String reason) {
        PaymentStatus previousStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.FAILED);
        payment.setProcessedAt(Instant.now());
        payment.setFailureCode(code);
        payment.setFailureReason(reason);
        if (previousStatus != PaymentStatus.FAILED && payment.getReservation()!=null) {
            notifyPaymentFailure(payment);
        }
    }
    private void markLatePaymentForReconciliation(Payment payment, Instant gatewayPaidAt) {
        if(payment.getRenewal()!=null) { markRenewalLatePayment(payment,gatewayPaidAt); return; }
        PaymentStatus previousStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(gatewayPaidAt);
        payment.setProcessedAt(Instant.now());
        payment.setFailureCode("PAYMENT_AFTER_DEADLINE");
        payment.setFailureReason(
            "VNPAY recorded money after the reservation deadline; manual financial reconciliation is required"
        );
        if (previousStatus != PaymentStatus.PAID) {
            Reservation reservation = payment.getReservation();
            notificationService.createNotification(
                reservation.getCustomer().getId(), NotificationType.PAYMENT,
                "Thanh toán đang được đối soát",
                "VNPay ghi nhận tiền của đơn " + reservation.getReservationCode()
                    + " sau thời hạn. StorageHub đang đối soát giao dịch.",
                reservation.getId()
            );
        }
    }
    private Instant paymentDeadline(Reservation reservation) {
        Instant deadline = reservation.getPaymentExpiresAt() != null
            ? reservation.getPaymentExpiresAt() : reservation.getHoldExpiresAt();
        if (deadline == null) {
            throw ApiExceptions.conflict("Reservation payment deadline is missing");
        }
        return deadline;
    }
    private Instant parseGatewayPayDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT))
                .atZone(ZONE)
                .toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
    private void validateQueryAmount(Payment payment, Map<String, String> response) {
        String amount = response.get("vnp_Amount");
        if (amount == null) return;
        try {
            if (Long.parseLong(amount) != amountMinor(payment.getAmount())) {
                throw ApiExceptions.conflict("VNPAY returned an unexpected transaction amount");
            }
        } catch (NumberFormatException exception) {
            throw ApiExceptions.conflict("VNPAY returned an invalid transaction amount");
        }
    }
    private void expireGraceReservationAfterFinalFailure(Payment failedPayment, Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.PAYMENT_GRACE) return;
        boolean hasAnotherUnresolvedAttempt = paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        ).stream().anyMatch(candidate -> !candidate.getId().equals(failedPayment.getId())
            && (candidate.getStatus() == PaymentStatus.PENDING
                || candidate.getStatus() == PaymentStatus.PROCESSING
                || candidate.getStatus() == PaymentStatus.NOT_RECEIVED));
        if (hasAnotherUnresolvedAttempt) return;

        Instant now = Instant.now();
        reservation.setStatus(ReservationStatus.EXPIRED);
        reservation.setExpiredAt(now);
        reservation.setArchivedAt(now);
        reservationRepository.saveAndFlush(reservation);
        auditLogService.recordMutation(
            null, "RESERVATION_EXPIRED_AFTER_VNPAY_FAILURE", "Reservation", reservation.getId(),
            reservation.getFacility().getId(),
            Map.of("status", ReservationStatus.PAYMENT_GRACE),
            Map.of("status", ReservationStatus.EXPIRED,
                "responseCode", failedPayment.getGatewayResponseCode())
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.RESERVATION,
            "Phiên thanh toán đã hết hạn",
            "VNPay xác nhận giao dịch của đơn " + reservation.getReservationCode()
                + " không thành công. Đơn đã hết hạn và bạn có thể tạo đơn mới.",
            reservation.getId()
        );
    }
    private VnpayTransactionResponse response(Payment payment, String message) {
        return new VnpayTransactionResponse(payment.getId(), payment.getReservation()==null?null:payment.getReservation().getId(), payment.getGatewayIntentId(),
            payment.getGatewayTransactionNo(), payment.getAmount(), payment.getCurrency(), payment.getRefundedAmount(), payment.getStatus(),
            payment.getReservation()==null?null:payment.getReservation().getStatus(), payment.getGatewayResponseCode(), payment.getGatewayTransactionStatus(),
            payment.getGatewayPayDate(), message, payment.getProcessedAt(), payment.getLastReconciledAt(),
            payment.getPurpose(), payment.getGatewayBankCode() == null || payment.getGatewayBankCode().isBlank()
                ? "VNPAY" : payment.getGatewayBankCode(),
            payment.getPaidAt(), payment.getInitiatedBy()==null?null:payment.getInitiatedBy().getFullName());
    }
    private String message(Payment payment) {
        return payment.getStatus() == PaymentStatus.PAID ? "Payment was recorded successfully"
            : payment.getStatus() == PaymentStatus.FAILED ? "Payment was not successful" : "Payment is awaiting VNPAY confirmation";
    }

    public record CallbackResult(String rspCode,String message,UUID paymentId,UUID reservationId,UUID renewalId,
        boolean success,boolean requiresReconciliation) {
        public CallbackResult(String rspCode,String message,UUID paymentId,UUID reservationId,boolean success) {
            this(rspCode,message,paymentId,reservationId,null,success,false);
        }
    }
    enum ReconciliationOutcome { SUCCESS, FINAL_FAILURE, UNKNOWN }
}
