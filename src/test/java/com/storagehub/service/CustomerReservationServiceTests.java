package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.CancelReservationRequest;
import com.storagehub.api.reservation.CheckInAppointmentRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.UpdateSetupGoodsRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CustomerReservationServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private CheckInRepository checkInRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RentalRepository rentalRepository;
    @Mock private StorageUnitRepository storageUnitRepository;
    @Mock private UnitAssignmentRepository assignmentRepository;
    @Mock private ReservationPricingSnapshotRepository snapshotRepository;
    @Mock private ReservationGoodsItemRepository goodsItemRepository;
    @Mock private com.storagehub.domain.repo.FileAssetRepository fileAssetRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private NotificationService notificationService;

    private CustomerReservationService service;
    private ActorPrincipal actor;
    private Reservation reservation;
    private ReservationPricingSnapshot snapshot;

    @BeforeEach
    void setUp() {
        service = new CustomerReservationService(
            reservationRepository, paymentRepository, checkInRepository, rentalRepository,
            storageUnitRepository, assignmentRepository, fileAssetRepository, snapshotRepository,
            goodsItemRepository, auditLogService, notificationService
        );

        User customer = entityWithId(new User());
        actor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );

        Facility facility = entityWithId(new Facility());
        UnitType unitType = entityWithId(new UnitType());
        unitType.setLengthM(new BigDecimal("4"));
        unitType.setWidthM(new BigDecimal("4"));
        unitType.setHeightM(new BigDecimal("4"));
        unitType.setMaxLoadKg(new BigDecimal("500"));
        unitType.setRackCount(4);
        unitType.setRackLengthM(new BigDecimal("2"));
        unitType.setRackWidthM(new BigDecimal("2"));
        unitType.setRackHeightM(new BigDecimal("2"));
        ReservationQuote quote = entityWithId(com.storagehub.testing.LegacyReservationQuoteFixture.create());
        quote.setUnitTypeSnapshotJson("{\"lengthM\":4,\"widthM\":4,\"heightM\":4,\"maxLoadKg\":500,\"rackCount\":4,\"rackLengthM\":2,\"rackWidthM\":2,\"rackHeightM\":2}");

        reservation = entityWithId(new Reservation());
        ReflectionTestUtils.setField(reservation, "createdAt", Instant.now());
        reservation.setReservationCode("RSV-TEST000001");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setSourceQuote(quote);
        reservation.setStatus(ReservationStatus.AWAITING_EMAIL);
        reservation.setGoodsReviewStatus(GoodsReviewStatus.NOT_REQUIRED);
        reservation.setCompatibilityResult(CompatibilityResult.COMPATIBLE);
        reservation.setStartDate(LocalDate.of(2026, 10, 10));
        reservation.setEndDate(LocalDate.of(2027, 1, 10));
        reservation.setTotalGoodsVolumeM3(new BigDecimal("1.000000"));
        reservation.setTotalGoodsWeightKg(new BigDecimal("20.00"));
        reservation.setHoldExpiresAt(Instant.now().plusSeconds(600));

        snapshot = new ReservationPricingSnapshot();
        snapshot.setNetRentalAmount(new BigDecimal("16005000.00"));
        snapshot.setReservationDepositAmount(new BigDecimal("6402000.00"));
        snapshot.setSecurityDepositAmount(new BigDecimal("5500000.00"));
        snapshot.setRemainingRentalAmount(new BigDecimal("9603000.00"));
        snapshot.setDueAtCheckIn(new BigDecimal("15103000.00"));
        snapshot.setTotalInitialObligation(new BigDecimal("21505000.00"));
    }

    @Test
    void amendsSetupGoodsOnceInPlaceAndKeepsStableIdentity() {
        Rental rental = entityWithId(new Rental());
        rental.setStatus(RentalStatus.setup);
        rental.setSetupStartedAt(Instant.now().minusSeconds(60));
        rental.setSetupEndsAt(Instant.now().plusSeconds(3600));
        ReservationGoodsItem stored = entityWithId(new ReservationGoodsItem());
        stored.setReservation(reservation);
        stored.setGoodsCode("GDS-STABLE01");
        stored.setCategory(GoodsCategory.FURNITURE);
        stored.setCategoryCode("FURNITURE");
        stored.setCategoryName("Đồ gia dụng");
        stored.setMaterialName("Gỗ");
        stored.setDescription("Khai báo ban đầu");
        stored.setQuantity(1);
        stored.setLengthCm(new BigDecimal("50"));
        stored.setWidthCm(new BigDecimal("40"));
        stored.setHeightCm(new BigDecimal("30"));
        stored.setWeightKg(new BigDecimal("10"));
        GoodsItemRequest changed = new GoodsItemRequest(
            GoodsCategory.FURNITURE, null, "Gỗ", null, "Thông tin sau khi sửa", null,
            2, new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("30"),
            new BigDecimal("12"), false
        );
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(rentalRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(rental));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(stored));
        when(snapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);

        var response = service.updateSetupGoods(actor, reservation.getId(),
            new UpdateSetupGoodsRequest(List.of(changed), "Đã đóng gói"));

        assertThat(stored.getGoodsCode()).isEqualTo("GDS-STABLE01");
        assertThat(stored.getCategoryCode()).isEqualTo("FURNITURE");
        assertThat(stored.getDescription()).isEqualTo("Thông tin sau khi sửa");
        assertThat(stored.getQuantity()).isEqualTo(2);
        assertThat(reservation.getSetupGoodsRevisionUsedAt()).isNotNull();
        assertThat(response.getGoodsItems()).hasSize(1);
        verify(goodsItemRepository, org.mockito.Mockito.never()).deleteAll(any());
    }

    @Test
    void blocksSetupGoodsAfterInventoryWasVerified() {
        Rental rental = entityWithId(new Rental());
        rental.setStatus(RentalStatus.setup);
        rental.setSetupStartedAt(Instant.now().minusSeconds(60));
        rental.setSetupEndsAt(Instant.now().plusSeconds(3600));
        rental.setInventoryVerifiedAt(Instant.now());
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(rentalRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(rental));

        assertThatThrownBy(() -> service.updateSetupGoods(actor, reservation.getId(),
            new UpdateSetupGoodsRequest(List.of(new GoodsItemRequest()), null)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("locked");
    }

    @Test
    void paymentHistoryIncludesArchivedReservationsAndScopesToCustomer() {
        reservation.setArchivedAt(Instant.now());
        reservation.setStatus(ReservationStatus.COMPLETED);
        Payment payment = entityWithId(new Payment());
        payment.setReservation(reservation);
        payment.setInitiatedBy(reservation.getCustomer());
        payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setStatus(PaymentStatus.PAID);
        payment.setAmount(new BigDecimal("100000"));
        payment.setCurrency("VND");
        ReflectionTestUtils.setField(payment, "createdAt", Instant.parse("2026-10-12T08:00:00Z"));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 50,
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                .and(org.springframework.data.domain.Sort.by("id")));
        when(paymentRepository.findAllByReservation_Customer_Id(actor.userId(), pageable))
            .thenReturn(new PageImpl<>(List.of(payment), pageable, 1));

        var history = service.paymentHistory(actor, 0, 50, "history-test");

        assertThat(history.data()).hasSize(1);
        assertThat(history.data().getFirst().reservationCode()).isEqualTo(reservation.getReservationCode());
        assertThat(history.data().getFirst().createdAt()).isEqualTo(payment.getCreatedAt());
        verify(paymentRepository).findAllByReservation_Customer_Id(actor.userId(), pageable);
        org.mockito.Mockito.verifyNoInteractions(snapshotRepository, reservationRepository);
    }

    @Test
    void paymentHistoryRejectsNonCustomerActors() {
        var staff = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.STAFF), Set.of(), Map.of());
        assertThatThrownBy(() -> service.paymentHistory(staff, 0, 50, "test")).isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(paymentRepository);
    }

    @Test
    void paymentHistoryRejectsInvalidPagination() {
        assertThatThrownBy(() -> service.paymentHistory(actor, -1, 50, "test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.paymentHistory(actor, 0, 101, "test")).isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(paymentRepository);
    }

    @Test
    void listsOnlyReservationsOwnedByCustomer() {
        when(reservationRepository.findAllByCustomer_IdAndArchivedAtIsNullAndStatusNot(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(reservation)));
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));

        var response = service.list(actor, null, 0, 20, "correlation-id");

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getId()).isEqualTo(reservation.getId());
        verify(reservationRepository).findAllByCustomer_IdAndArchivedAtIsNullAndStatusNot(
            eq(actor.userId()), eq(ReservationStatus.REJECTED), any()
        );
    }

    @Test
    void changesCheckInAppointmentAndNotifiesFacilityStaff() {
        service = new CustomerReservationService(
            reservationRepository, checkInRepository, paymentRepository, rentalRepository,
            storageUnitRepository, snapshotRepository, goodsItemRepository, auditLogService,
            notificationService
        );
        Instant registeredAt = Instant.parse("2026-10-19T01:00:00Z");
        ReflectionTestUtils.setField(reservation, "createdAt", registeredAt);
        reservation.setGoodsReviewStatus(com.storagehub.domain.model.GoodsReviewStatus.NOT_REQUIRED);
        Instant previousAppointment = ReservationCheckInPolicy.plannedWindow(registeredAt, false).availableAt();
        Instant newAppointment = previousAppointment.plusSeconds(1_800);
        reservation.setStatus(ReservationStatus.AWAITING_EMAIL);
        reservation.setAppointmentAt(previousAppointment);
        CheckIn checkIn = new CheckIn();
        checkIn.setStatus(CheckInStatus.scheduled);
        checkIn.setScheduledAt(previousAppointment);

        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(checkInRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(checkIn));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(snapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));

        var response = service.setCheckInAppointment(
            actor, reservation.getId(), new CheckInAppointmentRequest(newAppointment)
        );

        assertThat(response.getAppointmentAt()).isEqualTo(newAppointment);
        assertThat(checkIn.getScheduledAt()).isEqualTo(newAppointment);
        verify(notificationService).notifyStaffOfCheckInAppointmentChange(reservation, previousAppointment);
    }

    @Test
    void rejectsCheckInAppointmentBeforeRentalStartDate() {
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setStartDate(LocalDate.now().plusDays(2));
        Instant invalidAppointment = Instant.now().plusSeconds(3_600);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.setCheckInAppointment(
            actor, reservation.getId(), new CheckInAppointmentRequest(invalidAppointment)
        )).isInstanceOf(ApiException.class)
            .hasMessageContaining("permitted seven-day window");
    }

    @Test
    void preservesHistoricalDiscountSnapshotDetailsWhenRetrievingReservation() {
        snapshot.setRentalMonths(6);
        snapshot.setPricingPackageCode("PKG-6M");
        snapshot.setMonthlyPrice(new BigDecimal("2000000.00"));
        snapshot.setGrossRentalAmount(new BigDecimal("12000000.00"));
        snapshot.setDiscountRate(new BigDecimal("0.0800"));
        snapshot.setDiscountAmount(new BigDecimal("960000.00"));
        snapshot.setVoucherCode("HELLO2026");
        snapshot.setVoucherDiscountAmount(new BigDecimal("100000.00"));
        snapshot.setNetRentalAmount(new BigDecimal("11040000.00"));

        when(reservationRepository.findAllByCustomer_IdAndArchivedAtIsNullAndStatusNot(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(reservation)));
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));

        var response = service.list(actor, null, 0, 20, "correlation-id");

        assertThat(response.data()).hasSize(1);
        var item = response.data().get(0);
        assertThat(item.getPricingPackageCode()).isEqualTo("PKG-6M");
        assertThat(item.getRentalMonths()).isEqualTo(6);
        assertThat(item.getMonthlyPrice()).isEqualByComparingTo("2000000.00");
        assertThat(item.getGrossRentalAmount()).isEqualByComparingTo("12000000.00");
        assertThat(item.getDiscountRate()).isEqualByComparingTo("0.0800");
        assertThat(item.getDiscountAmount()).isEqualByComparingTo("960000.00");
        assertThat(item.getVoucherCode()).isEqualTo("HELLO2026");
        assertThat(item.getVoucherDiscountAmount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void cancelsReservationWhileItIsWaiting() {
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of());
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of());

        var response = service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Không còn nhu cầu")
        );

        assertThat(response.getReservation().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.getCancelReason()).isEqualTo("Không còn nhu cầu");
        assertThat(response.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelsConfirmedReservationWithoutRefundingDepositAndReleasesAssignedUnit() {
        reservation.setStatus(ReservationStatus.CONFIRMED);
        StorageUnit assignedUnit = entityWithId(new StorageUnit());
        assignedUnit.setStatus(StorageUnitStatus.assigned);
        reservation.setAssignedUnit(assignedUnit);
        UnitAssignment assignment = entityWithId(new UnitAssignment());
        assignment.setReservation(reservation);
        assignment.setStorageUnit(assignedUnit);
        assignment.setStatus(UnitAssignmentStatus.ACTIVE);
        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.PAID);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of(payment));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of());
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(assignment));

        service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Đổi kế hoạch")
        );

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservation.getAssignedUnit()).isNull();
        assertThat(assignedUnit.getStatus()).isEqualTo(StorageUnitStatus.available);
        assertThat(assignment.getStatus()).isEqualTo(UnitAssignmentStatus.CANCELLED);
        assertThat(assignment.getReleaseDisposition()).isEqualTo(UnitReleaseDisposition.AVAILABLE);
        verify(notificationService).notifyStaffOfReservationCancellation(reservation, true);
    }

    @Test
    void rejectsCancellationWhilePaymentIsProcessing() {
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.PROCESSING);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of(payment));

        assertThatThrownBy(() -> service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Đổi kế hoạch")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("payment is processing");
    }

    @Test
    void rejectsCancellationAfterCheckInFlowHasStarted() {
        reservation.setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Đổi kế hoạch")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void cancelsPendingPaymentWhenReservationIsCancelled() {
        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.PENDING);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of(payment));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of());

        service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Không còn nhu cầu")
        );

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void hidesReservationNotOwnedByCustomer() {
        when(reservationRepository.findByIdAndCustomer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(actor, reservation.getId()))
            .isInstanceOf(ApiException.class)
            .hasMessage("Reservation was not found");
    }

    @Test
    void preventsCancellingReservationOwnedByAnotherCustomer() {
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Ownership security test")
        ))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(404))
            .hasMessage("Reservation was not found");
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
