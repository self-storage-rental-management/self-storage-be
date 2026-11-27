package com.storagehub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.service.IntakePolicyProperties;
import com.storagehub.service.IntakeTimelineCalculator;
import com.storagehub.service.InventoryTimelinePolicy;
import com.storagehub.service.ReservationSnapshotCodec;
import com.storagehub.service.ViolationRecoveryWorkflow;
import com.storagehub.time.BusinessTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds 9 ready-to-test Setup & Inspection cases across every key stage/boundary
 * when STORAGEHUB_SEED_INTAKE_SCENARIOS=true (app.seed-intake-scenarios.enabled=true).
 */
@Configuration
@ConditionalOnProperty(prefix = "app.seed-intake-scenarios", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IntakeScenarioDemoSeeder {

    private final UserRepository userRepository;
    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final ReservationQuoteRepository quoteRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final UnitAssignmentRepository assignmentRepository;
    private final CheckInRepository checkInRepository;
    private final RentalRepository rentalRepository;
    private final PaymentRepository paymentRepository;
    private final FileAssetRepository fileAssetRepository;
    private final IntakeVerificationAttemptRepository verificationAttemptRepository;
    private final ReturnCaseRepository returnCaseRepository;
    private final IntakeTimelineCalculator timeline;
    private final ViolationRecoveryWorkflow violationRecovery;
    private final ViolationRecoveryRepository violationRecoveryRepository;
    private final MaintenanceTaskRepository maintenanceTaskRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Bean
    @Order(30)
    CommandLineRunner seedIntakeScenarios(PlatformTransactionManager transactionManager) {
        // The runner calls seedAll on this instance, bypassing its transactional proxy.
        // Keep the entire seed, including pessimistic locks, in one transaction.
        return args -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> seedAll());
    }

    @Transactional
    public void seedAll() {
        User customer = userRepository.findByEmailIgnoreCase("thanhthao725218@gmail.com")
            .or(() -> userRepository.findByEmailIgnoreCase("customer@storagehub.demo"))
            .orElse(null);
        User staff = userRepository.findByEmailIgnoreCase("staff.q1@storagehub.demo")
            .or(() -> userRepository.findByEmailIgnoreCase("staff@storagehub.demo"))
            .orElse(customer);
        Facility facility = facilityRepository.findByCodeIgnoreCase("HCM-Q1-F01")
            .or(() -> facilityRepository.findAll().stream().findFirst())
            .orElse(null);

        if (customer == null || staff == null || facility == null) {
            log.warn("Skipping intake scenario seeding: missing demo customer/staff/facility.");
            return;
        }

        Instant now = clock.instant();
        int created = 0;

        // 01: Kho S mới nhận kho (5 ngày, 0/2 gia hạn)
        created += createScenario("RSV-TEST-01", "TEST-S-01", "S", facility, customer, staff, now,
            now.minus(1, ChronoUnit.HOURS), false, null, null, 0, false, false, 0, null, null);

        // 02: Kho M sắp hết hạn D-48h (còn 47h -> scheduler tự gửi thông báo + email D-48h)
        created += createScenario("RSV-TEST-02", "TEST-M-02", "M", facility, customer, staff, now,
            now.minus(121, ChronoUnit.HOURS), false, null, null, 0, false, false, 0, null, null);

        // 03: Kho M có lịch hẹn A = now + 5h nhưng chưa báo setup -> quá C = A - 6h -> scheduler hủy sang NOT_READY
        created += createScenario("RSV-TEST-03", "TEST-M-03", "M", facility, customer, staff, now,
            now.minus(24, ChronoUnit.HOURS), false, now.plus(5, ChronoUnit.HOURS), IntakeTimelineCalculator.APPT_ACTIVE, 0, false, false, 0, null, null);

        // 04: Kho M còn 3h trước D, chưa báo setup, còn 1/2 lượt gia hạn -> chặn setup, cho gia hạn +3 ngày
        created += createScenario("RSV-TEST-04", "TEST-M-04", "M", facility, customer, staff, now,
            now.minus(237, ChronoUnit.HOURS), false, null, null, 1, false, false, 0, null, null);

        // 05: Kho M đã setup xong, quá giờ hẹn A + 35 phút chưa có mặt -> Staff test MARK_MISSED
        created += createScenario("RSV-TEST-05", "TEST-M-05", "M", facility, customer, staff, now,
            now.minus(48, ChronoUnit.HOURS), true, now.minus(35, ChronoUnit.MINUTES), IntakeTimelineCalculator.APPT_ACTIVE, 0, false, false, 0, null, null);

        // 06: Kho M đã setup xong, quá hạn D 5 phút -> scheduler chuyển PENDING_ATTENDANCE_REVIEW (R = D + 30m)
        created += createScenario("RSV-TEST-06", "TEST-M-06", "M", facility, customer, staff, now,
            now.minus(168, ChronoUnit.HOURS).minus(5, ChronoUnit.MINUTES), true, null, null, 0, false, false, 0, null, null);

        // 07: Kho M xác minh lần 1 FAIL lúc now - 2h -> đang trong cửa sổ 72h (lần 2)
        created += createScenario("RSV-TEST-07", "TEST-M-07", "M", facility, customer, staff, now,
            now.minus(72, ChronoUnit.HOURS), true, now.minus(3, ChronoUnit.HOURS), IntakeTimelineCalculator.APPT_ATTENDED, 0, true, false, 1, now.minus(2, ChronoUnit.HOURS), "CCCD mờ, chờ bản gốc");

        // 08: Kho M đã xác minh PASS, còn 5h trước F -> scheduler nhắc F-6h, Staff hoàn tất bước 2
        created += createScenario("RSV-TEST-08", "TEST-M-08", "M", facility, customer, staff, now,
            now.minus(100, ChronoUnit.HOURS), true, now.minus(68, ChronoUnit.HOURS), IntakeTimelineCalculator.APPT_ATTENDED, 0, true, true, 1, now.minus(67, ChronoUnit.HOURS), null);

        // 09: Kho L đã dùng hết 2/2 lượt gia hạn (chặn lần 3)
        created += createScenario("RSV-TEST-09", "TEST-L-09", "L", facility, customer, staff, now,
            now.minus(308, ChronoUnit.HOURS), false, null, null, 2, false, false, 0, null, null);

        // 10: Thu hồi do INVENTORY_NO_SHOW (quá D + 35p không bằng chứng -> ViolationRecovery AWAITING_ASSIGNMENT)
        created += createRecoveryScenario("RSV-TEST-10", "TEST-M-10", "M", facility, customer, staff, now,
            now.minus(168, ChronoUnit.HOURS).minus(35, ChronoUnit.MINUTES), true, false, 0, null,
            ViolationRecovery.Reason.INVENTORY_NO_SHOW, false, false);

        // 11: Thu hồi do INVENTORY_IDENTITY_FAILED (thất bại xác minh CCCD 2 lần -> ViolationRecovery AWAITING_ASSIGNMENT)
        created += createRecoveryScenario("RSV-TEST-11", "TEST-M-11", "M", facility, customer, staff, now,
            now.minus(48, ChronoUnit.HOURS), true, true, 2, now.minus(2, ChronoUnit.HOURS),
            ViolationRecovery.Reason.INVENTORY_IDENTITY_FAILED, false, false);

        // 12: Thu hồi do INVENTORY_TIMEOUT (quá cửa sổ 72h kiểm kê -> ViolationRecovery AWAITING_ASSIGNMENT)
        created += createRecoveryScenario("RSV-TEST-12", "TEST-M-12", "M", facility, customer, staff, now,
            now.minus(120, ChronoUnit.HOURS), true, true, 1, now.minus(73, ChronoUnit.HOURS),
            ViolationRecovery.Reason.INVENTORY_TIMEOUT, false, false);

        // 13: Thu hồi do REMOVAL_OVERDUE (kiểm kê sai lệch quá hạn mang hàng ra -> ViolationRecovery REVIEW_REQUIRED)
        created += createRecoveryScenario("RSV-TEST-13", "TEST-M-13", "M", facility, customer, staff, now,
            now.minus(190, ChronoUnit.HOURS), true, true, 1, now.minus(160, ChronoUnit.HOURS),
            ViolationRecovery.Reason.REMOVAL_OVERDUE, true, true);

        // 14: Thu hồi do SETUP_OVERDUE (chưa báo setup xong mà đã quá hạn D + 35p -> ViolationRecovery AWAITING_ASSIGNMENT)
        created += createRecoveryScenario("RSV-TEST-14", "TEST-M-14", "M", facility, customer, staff, now,
            now.minus(168, ChronoUnit.HOURS).minus(35, ChronoUnit.MINUTES), false, false, 0, null,
            ViolationRecovery.Reason.SETUP_OVERDUE, false, false);

        // 15: Trường hợp cần đối soát 30p CÓ bằng chứng đến đúng hạn (REVIEW_PENDING)
        created += createAttendanceReviewWithEvidenceScenario("RSV-TEST-15", "TEST-M-15", "M", facility, customer, staff, now);

        // 16: Trường hợp hàng kiểm kê sai lệch yêu cầu buộc trả (INVENTORY_MISMATCH + RETURN_REQUESTED)
        created += createMismatchForcedReturnScenario("RSV-TEST-16", "TEST-L-16", "L", facility, customer, staff, now);

        // 17-21: Bộ 5 đơn test thu hồi bổ sung (bản sao của 10-14)
        created += createRecoveryScenario("RSV-TEST-17", "TEST-M-17", "M", facility, customer, staff, now,
            now.minus(168, ChronoUnit.HOURS).minus(35, ChronoUnit.MINUTES), true, false, 0, null,
            ViolationRecovery.Reason.INVENTORY_NO_SHOW, false, false);

        created += createRecoveryScenario("RSV-TEST-18", "TEST-M-18", "M", facility, customer, staff, now,
            now.minus(48, ChronoUnit.HOURS), true, true, 2, now.minus(2, ChronoUnit.HOURS),
            ViolationRecovery.Reason.INVENTORY_IDENTITY_FAILED, false, false);

        created += createRecoveryScenario("RSV-TEST-19", "TEST-M-19", "M", facility, customer, staff, now,
            now.minus(120, ChronoUnit.HOURS), true, true, 1, now.minus(73, ChronoUnit.HOURS),
            ViolationRecovery.Reason.INVENTORY_TIMEOUT, false, false);

        created += createRecoveryScenario("RSV-TEST-20", "TEST-M-20", "M", facility, customer, staff, now,
            now.minus(190, ChronoUnit.HOURS), true, true, 1, now.minus(160, ChronoUnit.HOURS),
            ViolationRecovery.Reason.REMOVAL_OVERDUE, true, true);

        created += createRecoveryScenario("RSV-TEST-21", "TEST-M-21", "M", facility, customer, staff, now,
            now.minus(168, ChronoUnit.HOURS).minus(35, ChronoUnit.MINUTES), false, false, 0, null,
            ViolationRecovery.Reason.SETUP_OVERDUE, false, false);

        // 22: Đơn đang thu hồi (IN_PROGRESS), đã phân công staff và đang ghi nhận hàng lưu tạm
        created += createInProgressRecoveryScenario("RSV-TEST-22", "TEST-M-22", "M", facility, customer, staff, now);

        if (created > 0) {
            log.info("Seeded {} intake test-case scenarios for customer {} at facility {}", created, customer.getEmail(), facility.getCode());
        }
    }

    private int createScenario(
        String reservationCode,
        String unitCode,
        String sizeCode,
        Facility facility,
        User customer,
        User staff,
        Instant now,
        Instant receivedAt,
        boolean setupCompleted,
        Instant appointmentAt,
        String appointmentStatus,
        int extensionCount,
        boolean arrived,
        boolean verifiedPass,
        int verifAttemptsCount,
        Instant firstVerifAt,
        String firstFailReason
    ) {
        if (reservationRepository.existsByReservationCode(reservationCode)) {
            repairDemoSnapshots(reservationCode, facility);
            return 0;
        }
        UnitType unitType = unitTypeRepository.findByFacility_IdAndCode(facility.getId(), sizeCode)
            .orElseGet(() -> unitTypeRepository.findByFacility_IdOrderByCodeAsc(facility.getId()).stream().findFirst().orElse(null));
        if (unitType == null) return 0;

        StorageUnit unit = storageUnitRepository.findByCode(unitCode).orElseGet(() -> {
            StorageUnit u = new StorageUnit();
            u.setFacility(facility);
            u.setUnitType(unitType);
            u.setCode(unitCode);
            u.setFloor("Tầng 1");
            u.setZone("Khu Test");
            u.setStatus(StorageUnitStatus.reserved);
            u.setRackLayoutJson(unitType.getRackLayoutJson());
            return storageUnitRepository.saveAndFlush(u);
        });
        if (unit.getStatus() == StorageUnitStatus.available) {
            unit.setStatus(StorageUnitStatus.reserved);
            unit = storageUnitRepository.saveAndFlush(unit);
        }

        LocalDate paidStart = receivedAt.atZone(BusinessTime.ZONE).toLocalDate().plusDays(1);
        LocalDate contractEnd = paidStart.plusMonths(3);
        BigDecimal monthly = unitType.getMonthlyPrice() != null ? unitType.getMonthlyPrice() : new BigDecimal("2000000");
        BigDecimal gross = monthly.multiply(BigDecimal.valueOf(3));
        BigDecimal deposit = gross.multiply(new BigDecimal("0.20")).setScale(0, java.math.RoundingMode.HALF_UP);
        BigDecimal secDeposit = unitType.getSecurityDepositAmount() != null ? unitType.getSecurityDepositAmount() : monthly;
        BigDecimal remaining = gross.subtract(deposit);
        BigDecimal dueAtCheckIn = remaining.add(secDeposit);

        ReservationQuote quote = new ReservationQuote();
        quote.setCustomer(customer);
        quote.setFacility(facility);
        quote.setUnitType(unitType);
        quote.setPricingPackageCode("PKG-3M");
        quote.setPolicyVersion("v1.0");
        quote.setStartDate(paidStart);
        quote.setEndDate(contractEnd);
        quote.setRentalMonths(3);
        quote.setFreeSetupDays(1);
        quote.setMonthlyPrice(monthly);
        quote.setSubtotal(gross);
        quote.setDiscountRate(BigDecimal.ZERO);
        quote.setDiscountAmount(BigDecimal.ZERO);
        quote.setTotalAfterDiscount(gross);
        quote.setReservationDepositAmount(deposit);
        quote.setSecurityDepositAmount(secDeposit);
        quote.setRemainingRentalAmount(remaining);
        quote.setDueAtCheckIn(dueAtCheckIn);
        quote.setTotalInitialObligation(gross.add(secDeposit));
        Instant snapshotAt = receivedAt.minus(2, ChronoUnit.HOURS);
        quote.setGoodsSnapshotJson("[]");
        quote.setFacilitySnapshotJson(ReservationSnapshotCodec.facility(facility, snapshotAt));
        quote.setUnitTypeSnapshotJson(ReservationSnapshotCodec.unitType(unitType, snapshotAt));
        quote.setQuotedAt(snapshotAt);
        quote.setExpiresAt(receivedAt.plus(24, ChronoUnit.HOURS));
        quote = quoteRepository.saveAndFlush(quote);

        Reservation reservation = new Reservation();
        reservation.setReservationCode(reservationCode);
        reservation.setCustomer(customer);
        reservation.setSourceQuote(quote);
        reservation.setIdempotencyKey("seed-" + reservationCode);
        reservation.setIdentityDocumentNumber("079200012345");
        reservation.setCustomerPhone(customer.getPhone() != null ? customer.getPhone() : "0908123456");
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setAssignedUnit(unit);
        reservation.setStatus(ReservationStatus.PREPARING);
        reservation.setCompatibilityResult(CompatibilityResult.COMPATIBLE);
        reservation.setStartDate(paidStart);
        reservation.setEndDate(contractEnd);
        reservation.setSetupDaysSnapshot(1);
        reservation.setAppointmentAt(receivedAt.minus(30, ChronoUnit.MINUTES));
        reservation.setAmount(gross);
        reservation.setTotalGoodsVolumeM3(new BigDecimal("0.048000"));
        reservation.setTotalGoodsWeightKg(new BigDecimal("10.00"));
        reservation.setConfirmedAt(receivedAt.minus(1, ChronoUnit.HOURS));
        reservation.setDepositPaidAt(receivedAt.minus(1, ChronoUnit.HOURS));
        reservation = reservationRepository.saveAndFlush(reservation);

        ReservationPricingSnapshot snap = new ReservationPricingSnapshot();
        snap.setReservation(reservation);
        snap.setPricingPackageCode("PKG-3M");
        snap.setPricingPolicyVersion("v1.0");
        snap.setRentalMonths(3);
        snap.setFreeSetupDays(1);
        snap.setMonthlyPrice(monthly);
        snap.setGrossRentalAmount(gross);
        snap.setNetRentalAmount(gross);
        snap.setReservationDepositAmount(deposit);
        snap.setSecurityDepositAmount(secDeposit);
        snap.setRemainingRentalAmount(remaining);
        snap.setDueAtCheckIn(dueAtCheckIn);
        snap.setTotalInitialObligation(gross.add(secDeposit));
        snap.setQuotedAt(quote.getQuotedAt());
        snap.setExpiresAt(quote.getExpiresAt());
        snap.setFacilitySnapshotJson(quote.getFacilitySnapshotJson());
        snap.setUnitTypeSnapshotJson(quote.getUnitTypeSnapshotJson());
        snap.setAssignedUnitSnapshotJson(ReservationSnapshotCodec.storageUnit(unit, snapshotAt));
        snapshotRepository.saveAndFlush(snap);

        ReservationGoodsItem item = new ReservationGoodsItem();
        item.setGoodsCode("GDS-" + reservationCode);
        item.setReservation(reservation);
        item.setCategory(GoodsCategory.DOCUMENTS);
        item.setCustomGoodsName("Thùng tài liệu mẫu");
        item.setQuantity(2);
        item.setLengthCm(new BigDecimal("40.00"));
        item.setWidthCm(new BigDecimal("30.00"));
        item.setHeightCm(new BigDecimal("20.00"));
        item.setWeightKg(new BigDecimal("5.00"));
        item.setFragile(false);
        item.setStackable(true);
        item.setMaxStackLayers(3);
        goodsItemRepository.saveAndFlush(item);

        Payment balance = new Payment();
        balance.setInitiatedBy(staff);
        balance.setReservation(reservation);
        balance.setAmount(dueAtCheckIn);
        balance.setCurrency("VND");
        balance.setPurpose(PaymentType.RENTAL_BALANCE);
        balance.setStatus(PaymentStatus.PAID);
        balance.setPaidAt(receivedAt.minus(10, ChronoUnit.MINUTES));
        balance.setIdempotencyKey("seed-bal-" + reservationCode);
        balance.setGatewayIntentId("seed-intent-" + reservationCode);
        paymentRepository.saveAndFlush(balance);

        UnitAssignment assignment = new UnitAssignment();
        assignment.setReservation(reservation);
        assignment.setStorageUnit(unit);
        assignment.setStatus(UnitAssignmentStatus.ACTIVE);
        assignment.setAssignedBy(staff);
        assignment.setAssignedAt(receivedAt.minus(1, ChronoUnit.HOURS));
        assignmentRepository.saveAndFlush(assignment);

        Rental rental = new Rental();
        rental.setCustomer(customer);
        rental.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setReservation(reservation);
        rental.setMonthlyPrice(monthly);
        rental.setStatus(RentalStatus.setup);
        rental.setStartDate(paidStart);
        rental.setContractEndDate(contractEnd);
        rental.setSetupStartedAt(receivedAt);
        rental.setSetupEndsAt(receivedAt.plus(24, ChronoUnit.HOURS));
        rental.setTermsEffectiveAt(receivedAt);
        rental.setInventoryPending(true);
        timeline.initializeOnReceipt(rental, receivedAt);

        if (extensionCount > 0 && rental.getIntakeExtensionMaxHours() != null) {
            Instant newD = rental.getOriginalInspectionArrivalDeadlineAt()
                .plus(Math.multiplyExact((long) extensionCount, rental.getIntakeExtensionMaxHours()), ChronoUnit.HOURS);
            rental.setInventoryFinalDeadlineAt(newD);
            rental.setSetupCompletionDeadlineAt(newD);
            rental.setSetupExtensionCount(extensionCount);
        }
        Instant readyAt = setupCompleted ? receivedAt.plus(4, ChronoUnit.HOURS) : null;
        if (readyAt != null) {
            rental.setMoveInCompletedAt(readyAt);
        }
        if (appointmentAt != null) {
            rental.setInventoryAppointmentAt(appointmentAt);
            rental.setAppointmentStatus(appointmentStatus);
            rental.setAppointmentVersion(1);
        }
        if (arrived) {
            Instant arrivedAt = appointmentAt != null ? appointmentAt : now.minus(3, ChronoUnit.HOURS);
            rental.setActualArrivedAt(arrivedAt);
            rental.setArrivalRecordedAt(arrivedAt);
            rental.setArrivalRecordedBy(staff.getId());
            rental.setInventoryArrivedAt(arrivedAt);
        }
        if (firstVerifAt != null) {
            rental.setFirstIdentityVerificationAt(firstVerifAt);
            rental.setInspectionCompletionDeadlineAt(timeline.completionDeadline(firstVerifAt));
            rental.setVerificationAttemptCount(verifAttemptsCount);
            rental.setVerificationStatus(verifiedPass
                ? IntakeTimelineCalculator.VERIF_PASSED
                : IntakeTimelineCalculator.VERIF_IN_PROGRESS);
            if (verifiedPass) {
                rental.setInventoryIdentityVerifiedAt(firstVerifAt);
                rental.setInventoryIdentityVerifiedBy(staff.getId());
                rental.setInspectionStartedAt(firstVerifAt);
            }
        }
        rental = rentalRepository.saveAndFlush(rental);

        CheckIn checkIn = new CheckIn();
        checkIn.setReservation(reservation);
        checkIn.setRental(rental);
        checkIn.setPerformedBy(staff);
        checkIn.setAssignedStaff(staff);
        checkIn.setStatus(setupCompleted ? CheckInStatus.setup_completed : CheckInStatus.setup_in_progress);
        checkIn.setScheduledAt(receivedAt.minus(30, ChronoUnit.MINUTES));
        checkIn.setCheckedInAt(receivedAt.minus(20, ChronoUnit.MINUTES));
        checkIn.setCustomerReceiptConfirmedAt(receivedAt);
        checkIn.setIdentityVerifiedAt(receivedAt.minus(15, ChronoUnit.MINUTES));
        checkIn.setContractSignedAt(receivedAt.minus(12, ChronoUnit.MINUTES));
        checkIn.setPaymentCollectedAt(receivedAt.minus(10, ChronoUnit.MINUTES));
        checkIn.setSetupStartedAt(receivedAt);
        checkIn.setSetupDueAt(rental.getSetupEndsAt());
        checkIn.setSetupCompletedAt(readyAt);
        checkIn.setInventoryAppointmentAt(appointmentAt);
        checkIn.setInventoryArrivedAt(rental.getInventoryArrivedAt());
        checkIn.setLockReference("LOCK-" + reservationCode);
        checkIn = checkInRepository.saveAndFlush(checkIn);

        if (verifiedPass && firstVerifAt != null) {
            FileAsset photo = new FileAsset();
            photo.setUploadedBy(staff);
            photo.setStorageKey("demo/" + reservationCode + "-id.jpg");
            photo.setOriginalName("identity-demo.jpg");
            photo.setContentType("image/jpeg");
            photo.setSizeBytes(1024);
            photo.setChecksumSha256(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
            photo.setEntityType("CHECK_IN");
            photo.setEntityId(checkIn.getId());
            photo.setStatus(FileAssetStatus.ACTIVE);
            photo = fileAssetRepository.saveAndFlush(photo);
            checkIn.setInventoryIdentityPhotoId(photo.getId());
            checkIn.setInventoryIdentityVerifiedAt(firstVerifAt);
            checkIn.setInventoryIdentityVerifiedBy(staff);
            checkInRepository.saveAndFlush(checkIn);
            rental.setInventoryIdentityPhotoId(photo.getId());
            rentalRepository.saveAndFlush(rental);
        }

        if (firstVerifAt != null) {
            IntakeVerificationAttempt attempt = new IntakeVerificationAttempt();
            attempt.setRental(rental);
            attempt.setSubstantiveOrdinal(1);
            attempt.setOutcome(verifiedPass ? "PASS" : "FAIL");
            attempt.setRecordedAt(firstVerifAt);
            attempt.setStaffId(staff.getId());
            attempt.setDocumentNumberMasked("********2345");
            attempt.setReason(firstFailReason);
            verificationAttemptRepository.saveAndFlush(attempt);
        }

        return 1;
    }

    private int createRecoveryScenario(
        String reservationCode,
        String unitCode,
        String sizeCode,
        Facility facility,
        User customer,
        User staff,
        Instant now,
        Instant receivedAt,
        boolean setupCompleted,
        boolean arrived,
        int verifAttemptsCount,
        Instant firstVerifAt,
        ViolationRecovery.Reason vrReason,
        boolean removalOverdue,
        boolean reviewRequired
    ) {
        int created = createScenario(reservationCode, unitCode, sizeCode, facility, customer, staff, now,
            receivedAt, setupCompleted, null, arrived ? IntakeTimelineCalculator.APPT_ATTENDED : null,
            0, arrived, verifAttemptsCount == 1 && (vrReason == ViolationRecovery.Reason.INVENTORY_TIMEOUT || removalOverdue),
            verifAttemptsCount, firstVerifAt,
            vrReason == ViolationRecovery.Reason.INVENTORY_IDENTITY_FAILED ? "Sai số CCCD 2 lần" : null);
        if (created == 0) return 0;

        Reservation reservation = reservationRepository.findByReservationCode(reservationCode).orElse(null);
        if (reservation == null) return created;
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId()).orElse(null);
        if (rental == null) return created;

        if (vrReason == ViolationRecovery.Reason.INVENTORY_IDENTITY_FAILED) {
            rental.setVerificationStatus(IntakeTimelineCalculator.VERIF_FAILED_MAX_ATTEMPTS);
            rental.setInspectionFailureReason("Xác minh danh tính thất bại 2 lần.");
        } else if (vrReason == ViolationRecovery.Reason.INVENTORY_TIMEOUT) {
            rental.setInspectionClockStartedAt(firstVerifAt);
            rental.setInspectionCompletionDeadlineAt(firstVerifAt.plus(72, ChronoUnit.HOURS));
            rental.setInspectionFailureReason("Quá hạn hoàn tất kiểm kê 72 giờ.");
        } else if (removalOverdue) {
            rental.setInventoryResultPublishedAt(now.minus(120, ChronoUnit.HOURS));
            rental.setInventoryRemovalPublishedAt(now.minus(120, ChronoUnit.HOURS));
            rental.setInventoryRemovalDeadlineAt(now.minus(2, ChronoUnit.HOURS));
        } else {
            rental.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_CONFIRMED_ABSENT);
            rental.setInspectionFailureReason("Quá hạn đến kiểm kê và không có bằng chứng.");
        }

        Instant sourceDeadline = removalOverdue ? rental.getInventoryRemovalDeadlineAt()
            : vrReason == ViolationRecovery.Reason.INVENTORY_TIMEOUT ? rental.getInspectionCompletionDeadlineAt()
            : vrReason == ViolationRecovery.Reason.INVENTORY_IDENTITY_FAILED ? now.minus(1, ChronoUnit.MINUTES)
            : timeline.arrivalDeadline(rental);
        if (sourceDeadline == null || sourceDeadline.isAfter(now)) {
            sourceDeadline = now.minus(1, ChronoUnit.MINUTES);
        }
        violationRecovery.detect(rental, null, vrReason, sourceDeadline, now, reviewRequired);
        if (!reviewRequired) {
            rental.setInventoryEscalatedAt(now);
        }
        rentalRepository.saveAndFlush(rental);
        return created;
    }

    private int createInProgressRecoveryScenario(
        String reservationCode,
        String unitCode,
        String sizeCode,
        Facility facility,
        User customer,
        User staff,
        Instant now
    ) {
        int created = createRecoveryScenario(reservationCode, unitCode, sizeCode, facility, customer, staff, now,
            now.minus(120, ChronoUnit.HOURS), true, true, 1, now.minus(73, ChronoUnit.HOURS),
            ViolationRecovery.Reason.INVENTORY_TIMEOUT, false, false);
        if (created == 0) return 0;

        Reservation reservation = reservationRepository.findByReservationCode(reservationCode).orElse(null);
        if (reservation == null) return created;
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId()).orElse(null);
        if (rental == null) return created;
        ViolationRecovery recovery = violationRecoveryRepository.findByRental_IdOrderByCreatedAtDesc(rental.getId())
            .stream().findFirst().orElse(null);
        if (recovery == null || recovery.getTask() == null) return created;

        MaintenanceTask task = recovery.getTask();
        task.setAssignedTo(staff);
        task.setStatus(MaintenanceTaskStatus.in_progress);
        task.setStartedAt(now.minus(30, ChronoUnit.MINUTES));
        maintenanceTaskRepository.saveAndFlush(task);

        FileAsset evidence = new FileAsset();
        evidence.setUploadedBy(staff);
        evidence.setStorageKey("demo/" + reservationCode + "-recovery.jpg");
        evidence.setOriginalName("recovery-evidence.jpg");
        evidence.setContentType("image/jpeg");
        evidence.setSizeBytes(1024);
        evidence.setChecksumSha256(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        evidence.setEntityType("MAINTENANCE_TASK");
        evidence.setEntityId(task.getId());
        evidence.setStatus(FileAssetStatus.ACTIVE);
        evidence = fileAssetRepository.saveAndFlush(evidence);

        ViolationRecoveryWorkflow.Details details = new ViolationRecoveryWorkflow.Details();
        details.notifiedAt = now.minus(2, ChronoUnit.HOURS);
        details.acceptedAt = now.minus(1, ChronoUnit.HOURS);
        details.startedAt = now.minus(30, ChronoUnit.MINUTES);
        details.plannedHandover = "Khu lưu tạm A1";
        details.beforeEvidenceId = evidence.getId();
        details.needsWitness = false;
        details.items.put("KH-TEST22-1", new ViolationRecoveryWorkflow.Item(
            "KH-TEST22-1", "Thùng hồ sơ kế toán", "Nguyên vẹn", "Khu lưu tạm A1",
            evidence.getId(), "STORED", null, null, null));
        details.items.put("KH-TEST22-2", new ViolationRecoveryWorkflow.Item(
            "KH-TEST22-2", "Thùng hồ sơ kế toán", "Nguyên vẹn", "Khu lưu tạm A1",
            evidence.getId(), "STORED", null, null, null));
        details.history.add(new ViolationRecoveryWorkflow.HistoryEvent(
            "START", staff.getFullName(), now.minus(30, ChronoUnit.MINUTES),
            ViolationRecovery.Stage.IN_PROGRESS, "Bắt đầu thu hồi kho theo lịch."));

        try {
            recovery.setDetailsJson(objectMapper.writeValueAsString(details));
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể tạo detailsJson cho scenario thu hồi.", ex);
        }
        recovery.setStage(ViolationRecovery.Stage.IN_PROGRESS);
        recovery.setScheduledAt(now.minus(1, ChronoUnit.HOURS));
        recovery.setDeadlineAt(now.plus(47, ChronoUnit.HOURS));
        recovery.setActionDueAt(now.plus(47, ChronoUnit.HOURS));
        violationRecoveryRepository.saveAndFlush(recovery);
        return created;
    }

    private int createAttendanceReviewWithEvidenceScenario(
        String reservationCode,
        String unitCode,
        String sizeCode,
        Facility facility,
        User customer,
        User staff,
        Instant now
    ) {
        Instant receivedAt = now.minus(168, ChronoUnit.HOURS).minus(10, ChronoUnit.MINUTES);
        int created = createScenario(reservationCode, unitCode, sizeCode, facility, customer, staff, now,
            receivedAt, true, null, null, 0, false, false, 0, null, null);
        if (created == 0) return 0;

        Reservation reservation = reservationRepository.findByReservationCode(reservationCode).orElse(null);
        if (reservation == null) return created;
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId()).orElse(null);
        if (rental == null) return created;

        Instant d = timeline.arrivalDeadline(rental);
        rental.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_PENDING);
        rental.setAttendanceReviewDeadlineAt(timeline.reconciliationDeadline(d));
        rental.setActualArrivedAt(d.minus(2, ChronoUnit.MINUTES));
        rental.setAttendanceEvidenceNote("Sổ quầy lễ tân xác nhận khách có mặt đúng hạn trước mốc D.");
        rentalRepository.saveAndFlush(rental);
        return created;
    }

    private int createMismatchForcedReturnScenario(
        String reservationCode,
        String unitCode,
        String sizeCode,
        Facility facility,
        User customer,
        User staff,
        Instant now
    ) {
        Instant receivedAt = now.minus(72, ChronoUnit.HOURS);
        Instant firstVerifAt = now.minus(4, ChronoUnit.HOURS);
        int created = createScenario(reservationCode, unitCode, sizeCode, facility, customer, staff, now,
            receivedAt, true, now.minus(5, ChronoUnit.HOURS), IntakeTimelineCalculator.APPT_ATTENDED,
            0, true, true, 1, firstVerifAt, null);
        if (created == 0) return 0;

        Reservation reservation = reservationRepository.findByReservationCode(reservationCode).orElse(null);
        if (reservation == null) return created;
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId()).orElse(null);
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (rental == null || checkIn == null) return created;

        Instant publishedAt = now.minus(2, ChronoUnit.HOURS);
        checkIn.setInventoryFullFit(false);
        checkIn.setInventoryResolutionStatus(InventoryResolutionStatus.RETURN_REQUESTED);
        checkIn.setInventoryDecisionAt(publishedAt);
        checkIn.setInventoryDecisionNote("Hàng vượt quá kích thước kho thuê, buộc trả kho do kiểm kê sai lệch.");
        checkIn.setInventoryActualJson("{\"items\":[{\"name\":\"Tủ lạnh công nghiệp quá khổ\",\"quantity\":2}],\"mismatch\":true}");
        checkInRepository.saveAndFlush(checkIn);

        rental.setStatus(RentalStatus.return_requested);
        rental.setInventoryResultPublishedAt(publishedAt);
        rental.setInventoryRemovalPublishedAt(publishedAt);
        InventoryTimelinePolicy.initializeRemovalWindow(rental, publishedAt);
        rentalRepository.saveAndFlush(rental);

        ReturnCase rc = new ReturnCase();
        rc.setRental(rental);
        rc.setCustomer(customer);
        rc.setFacility(facility);
        rc.setRequestedBy(staff);
        rc.setStorageUnit(rental.getStorageUnit());
        rc.setStatus(ReturnCaseStatus.requested);
        rc.setReturnType(ReturnType.INVENTORY_MISMATCH);
        rc.setRequestedAt(publishedAt);
        rc.setScheduledDate(publishedAt.atZone(BusinessTime.ZONE).toLocalDate().plusDays(2));
        rc.setCustomerNotes("Buộc trả kho do hàng kiểm kê sai lệch kích thước.");
        returnCaseRepository.saveAndFlush(rc);
        return created;
    }

    private void repairDemoSnapshots(String reservationCode, Facility fallbackFacility) {
        if (reservationCode == null || !reservationCode.startsWith("RSV-TEST-")) return;
        Reservation reservation = reservationRepository.findByReservationCode(reservationCode).orElse(null);
        if (reservation == null) return;
        Facility facility = reservation.getFacility() != null ? reservation.getFacility() : fallbackFacility;
        UnitType unitType = reservation.getUnitType();
        StorageUnit assignedUnit = reservation.getAssignedUnit();
        Instant capturedAt = reservation.getCreatedAt() != null ? reservation.getCreatedAt() : clock.instant();

        ReservationQuote quote = reservation.getSourceQuote();
        if (quote != null) {
            boolean dirty = false;
            if (facility != null && (quote.getFacilitySnapshotJson() == null || quote.getFacilitySnapshotJson().isBlank() || "{}".equals(quote.getFacilitySnapshotJson().trim()))) {
                quote.setFacilitySnapshotJson(ReservationSnapshotCodec.facility(facility, capturedAt));
                dirty = true;
            }
            if (unitType != null && (quote.getUnitTypeSnapshotJson() == null || quote.getUnitTypeSnapshotJson().isBlank() || "{}".equals(quote.getUnitTypeSnapshotJson().trim()))) {
                quote.setUnitTypeSnapshotJson(ReservationSnapshotCodec.unitType(unitType, capturedAt));
                dirty = true;
            }
            if (dirty) quoteRepository.saveAndFlush(quote);
        }

        snapshotRepository.findByReservation_Id(reservation.getId()).ifPresent(snap -> {
            boolean dirty = false;
            if (facility != null && (snap.getFacilitySnapshotJson() == null || snap.getFacilitySnapshotJson().isBlank() || "{}".equals(snap.getFacilitySnapshotJson().trim()))) {
                snap.setFacilitySnapshotJson(ReservationSnapshotCodec.facility(facility, capturedAt));
                dirty = true;
            }
            if (unitType != null && (snap.getUnitTypeSnapshotJson() == null || snap.getUnitTypeSnapshotJson().isBlank() || "{}".equals(snap.getUnitTypeSnapshotJson().trim()))) {
                snap.setUnitTypeSnapshotJson(ReservationSnapshotCodec.unitType(unitType, capturedAt));
                dirty = true;
            }
            if (assignedUnit != null && (snap.getAssignedUnitSnapshotJson() == null || snap.getAssignedUnitSnapshotJson().isBlank() || "{}".equals(snap.getAssignedUnitSnapshotJson().trim()))) {
                snap.setAssignedUnitSnapshotJson(ReservationSnapshotCodec.storageUnit(assignedUnit, capturedAt));
                dirty = true;
            }
            if (dirty) snapshotRepository.saveAndFlush(snap);
        });
    }
}
