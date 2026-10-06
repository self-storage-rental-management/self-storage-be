package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.storagehub.api.payment.CreatePaymentComplaintRequest;
import com.storagehub.api.payment.PaymentComplaintDecisionRequest;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class PaymentComplaintServiceTests {
    @Mock PaymentComplaintRepository complaintRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock FileAssetRepository fileAssetRepository;
    @Mock ReservationGoodsItemRepository goodsItemRepository;
    @Mock ReservationPricingSnapshotRepository snapshotRepository;
    @Mock UserRepository userRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock FacilityScopeService facilityScopeService;
    @Mock NotificationService notificationService;
    @Mock AuditLogService auditLogService;

    PaymentComplaintService service;
    User customer;
    User manager;
    Reservation reservation;
    Payment payment;
    PaymentComplaint complaint;
    ActorPrincipal customerActor;
    ActorPrincipal managerActor;

    @BeforeEach
    void setUp() {
        service = new PaymentComplaintService(
            complaintRepository, reservationRepository, paymentRepository, fileAssetRepository,
            goodsItemRepository, snapshotRepository, userRepository,
            authorizationService, facilityScopeService, notificationService,
            auditLogService
        );
        customer = entity(new User());
        manager = entity(new User());
        Facility facility = entity(new Facility());
        UnitType unitType = entity(new UnitType());
        unitType.setFacility(facility);
        reservation = entity(new Reservation());
        reservation.setReservationCode("SH-TEST-1");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setStartDate(LocalDate.now().plusDays(1));
        reservation.setEndDate(LocalDate.now().plusMonths(1));
        payment = entity(new Payment());
        payment.setReservation(reservation);
        payment.setInitiatedBy(customer);
        payment.setAmount(new BigDecimal("400000.00"));
        payment.setCurrency("VND");
        payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setStatus(PaymentStatus.NOT_RECEIVED);
        complaint = entity(new PaymentComplaint());
        complaint.setReservation(reservation);
        complaint.setPayment(payment);
        complaint.setCustomer(customer);
        complaint.setReason("Đã chuyển tiền");
        complaint.setSubmittedAt(Instant.now());
        complaint.setReviewDueAt(Instant.now().plusSeconds(3600));
        customerActor = new ActorPrincipal(customer.getId(), UUID.randomUUID(),
            Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());
        managerActor = new ActorPrincipal(manager.getId(), UUID.randomUUID(),
            Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.VIEW_PAYMENTS.code(), SystemPermission.MANAGE_PAYMENTS.code()),
            Map.of());
        lenient().when(fileAssetRepository.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc(any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void submitMovesReservationToPaymentReviewAndLinksImages() {
        reservation.setStatus(ReservationStatus.PAYMENT_GRACE);
        reservation.setComplaintExpiresAt(Instant.now().plusSeconds(600));
        FileAsset image = entity(new FileAsset());
        image.setUploadedBy(customer);
        image.setContentType("image/png");
        image.setOriginalName("receipt.png");
        image.setSizeBytes(128);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), customer.getId()))
            .thenReturn(Optional.of(reservation));
        when(complaintRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());
        when(paymentRepository.findReservationPaymentsForUpdate(reservation.getId(), PaymentType.RESERVATION_DEPOSIT))
            .thenReturn(List.of(payment));
        when(fileAssetRepository.findAllById(List.of(image.getId()))).thenReturn(List.of(image));
        when(fileAssetRepository.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc(
            eq("PAYMENT_COMPLAINT"), any()
        )).thenReturn(List.of(image));
        when(complaintRepository.saveAndFlush(any())).thenAnswer(inv -> entity(inv.getArgument(0)));

        var response = service.submit(customerActor, reservation.getId(),
            new CreatePaymentComplaintRequest("Đã chuyển tiền", List.of(image.getId())));

        assertThat(response.status()).isEqualTo(PaymentComplaintStatus.PENDING);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_REVIEW);
        assertThat(image.getEntityType()).isEqualTo("PAYMENT_COMPLAINT");
        assertThat(response.images()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(image.getId());
            assertThat(item.downloadUrl()).isEqualTo("/api/files/" + image.getId());
        });
    }

    @Test
    void submitCreatesUnreceivedPaymentWhenGraceStartedWithoutATransaction() {
        reservation.setStatus(ReservationStatus.PAYMENT_GRACE);
        reservation.setComplaintExpiresAt(Instant.now().plusSeconds(600));
        ReservationPricingSnapshot snapshot = entity(new ReservationPricingSnapshot());
        snapshot.setReservation(reservation);
        snapshot.setReservationDepositAmount(new BigDecimal("400000.00"));
        FileAsset image = entity(new FileAsset());
        image.setUploadedBy(customer);
        image.setContentType("image/png");
        image.setOriginalName("receipt.png");
        image.setSizeBytes(128);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), customer.getId()))
            .thenReturn(Optional.of(reservation));
        when(complaintRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());
        when(paymentRepository.findReservationPaymentsForUpdate(reservation.getId(), PaymentType.RESERVATION_DEPOSIT))
            .thenReturn(List.of());
        when(snapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> entity(inv.getArgument(0)));
        when(fileAssetRepository.findAllById(List.of(image.getId()))).thenReturn(List.of(image));
        when(complaintRepository.saveAndFlush(any())).thenAnswer(inv -> entity(inv.getArgument(0)));

        var response = service.submit(customerActor, reservation.getId(),
            new CreatePaymentComplaintRequest("Tài khoản đã bị trừ tiền", List.of(image.getId())));

        assertThat(response.status()).isEqualTo(PaymentComplaintStatus.PENDING);
        assertThat(response.depositAmount()).isEqualByComparingTo("400000.00");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_REVIEW);
        verify(paymentRepository).saveAndFlush(any(Payment.class));
    }

    @Test
    void withdrawCancelsAndArchivesReservation() {
        reservation.setStatus(ReservationStatus.PAYMENT_REVIEW);
        when(complaintRepository.findById(complaint.getId())).thenReturn(Optional.of(complaint));
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), customer.getId()))
            .thenReturn(Optional.of(reservation));
        when(complaintRepository.findByIdForUpdate(complaint.getId())).thenReturn(Optional.of(complaint));
        when(complaintRepository.saveAndFlush(complaint)).thenReturn(complaint);

        service.withdraw(customerActor, complaint.getId());

        assertThat(complaint.getStatus()).isEqualTo(PaymentComplaintStatus.WITHDRAWN);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getArchivedAt()).isNotNull();
    }

    @Test
    void managerApproveConfirmsReservation() {
        reservation.setStatus(ReservationStatus.PAYMENT_REVIEW);
        when(complaintRepository.findById(complaint.getId())).thenReturn(Optional.of(complaint));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(complaintRepository.findByIdForUpdate(complaint.getId())).thenReturn(Optional.of(complaint));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(paymentRepository.findReservationPaymentsForUpdate(reservation.getId(), PaymentType.RESERVATION_DEPOSIT))
            .thenReturn(List.of(payment));
        when(complaintRepository.saveAndFlush(complaint)).thenReturn(complaint);

        service.decide(managerActor, complaint.getId(),
            new PaymentComplaintDecisionRequest(PaymentComplaintDecisionRequest.Decision.APPROVE, null));

        assertThat(complaint.getStatus()).isEqualTo(PaymentComplaintStatus.APPROVED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void managerRejectArchivesAndReleasesReservationCapacity() {
        reservation.setStatus(ReservationStatus.PAYMENT_REVIEW);
        when(complaintRepository.findById(complaint.getId())).thenReturn(Optional.of(complaint));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(complaintRepository.findByIdForUpdate(complaint.getId())).thenReturn(Optional.of(complaint));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(paymentRepository.findReservationPaymentsForUpdate(reservation.getId(), PaymentType.RESERVATION_DEPOSIT))
            .thenReturn(List.of(payment));
        when(complaintRepository.saveAndFlush(complaint)).thenReturn(complaint);

        service.decide(managerActor, complaint.getId(),
            new PaymentComplaintDecisionRequest(PaymentComplaintDecisionRequest.Decision.REJECT, "Không tìm thấy giao dịch"));

        assertThat(complaint.getStatus()).isEqualTo(PaymentComplaintStatus.REJECTED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(reservation.getArchivedAt()).isNotNull();
    }

    @Test
    void managerReviewQueueMapsComplaintPriority() {
        reservation.setStatus(ReservationStatus.PAYMENT_REVIEW);
        complaint.setStatus(PaymentComplaintStatus.REVIEW_OVERDUE);
        Object[] row = new Object[] {reservation, complaint};
        when(complaintRepository.findManagerPaymentQueue(any(), anyBoolean(), any(), any()))
            .thenReturn(new PageImpl<>(List.<Object[]>of(row)));

        var response = service.managerReviewQueue(managerActor, null, 0, 20, "correlation-id");

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().priority()).isZero();
        assertThat(response.data().getFirst().complaintStatus())
            .isEqualTo(PaymentComplaintStatus.REVIEW_OVERDUE);
    }

    private <T> T entity(T value) {
        if (value instanceof BaseEntity base && base.getId() == null) {
            ReflectionTestUtils.setField(base, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(base, "createdAt", Instant.now());
        }
        return value;
    }
}
