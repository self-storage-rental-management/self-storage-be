package com.storagehub.config;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.service.IntakePolicyProperties;
import com.storagehub.service.IntakeTimelineCalculator;
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
import org.springframework.transaction.annotation.Transactional;

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
    private final IntakeTimelineCalculator timeline;
    private final Clock clock;

    @Bean
    @Order(30)
    CommandLineRunner seedIntakeScenarios() {
        return args -> seedAll();
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
        quote.setGoodsSnapshotJson("[]");
        quote.setUnitTypeSnapshotJson("{}");
        quote.setQuotedAt(receivedAt.minus(2, ChronoUnit.HOURS));
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
}
