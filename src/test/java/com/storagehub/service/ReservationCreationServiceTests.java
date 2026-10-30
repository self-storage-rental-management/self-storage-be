package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.CompatibilityCheckResponse;
import com.storagehub.api.reservation.CreateReservationRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationQuoteRepository;
import com.storagehub.domain.repo.ReservationRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationCreationServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationQuoteRepository quoteRepository;
    @Mock private ReservationGoodsItemRepository goodsItemRepository;
    @Mock private ReservationPricingSnapshotRepository snapshotRepository;
    @Mock private RentalPackagePolicyRepository policyRepository;
    @Mock private ReservationCompatibilityService compatibilityService;
    @Mock private AuditLogService auditLogService;
    @Mock private ReservationCapacityService capacityService;

    private ReservationCreationService service;
    private ActorPrincipal actor;
    private ReservationQuote quote;
    private RentalPackagePolicy policy;

    @BeforeEach
    void setUp() {
        service = new ReservationCreationService(
            reservationRepository, quoteRepository, goodsItemRepository, snapshotRepository,
            policyRepository, compatibilityService, auditLogService, capacityService
        );

        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());

        UnitType unitType = new UnitType();
        ReflectionTestUtils.setField(unitType, "id", UUID.randomUUID());
        unitType.setFacility(facility);
        unitType.setMonthlyPrice(new BigDecimal("5500000.00"));

        User customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        actor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );

        quote = com.storagehub.testing.LegacyReservationQuoteFixture.create();
        ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
        quote.setCustomer(customer);
        quote.setFacility(facility);
        quote.setUnitType(unitType);
        quote.setPricingPackageCode("THREE_MONTHS");
        quote.setPolicyVersion("2026-01");
        quote.setStartDate(LocalDate.of(2026, 10, 10));
        quote.setEndDate(LocalDate.of(2027, 1, 10));
        quote.setRentalMonths(3);
        quote.setMonthlyPrice(new BigDecimal("5500000.00"));
        quote.setSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        quote.setSetupEarlyExitFee(new BigDecimal("5500000.00"));
        quote.setSubtotal(new BigDecimal("16500000.00"));
        quote.setDiscountRate(new BigDecimal("0.0300"));
        quote.setDiscountAmount(new BigDecimal("495000.00"));
        quote.setTotalAfterDiscount(new BigDecimal("16005000.00"));
        quote.setReservationDepositAmount(new BigDecimal("4301000.00"));
        quote.setSecurityDepositAmount(new BigDecimal("5500000.00"));
        quote.setRemainingRentalAmount(new BigDecimal("11704000.00"));
        quote.setDueAtCheckIn(new BigDecimal("17204000.00"));
        quote.setTotalInitialObligation(new BigDecimal("21505000.00"));
        quote.setQuotedAt(Instant.now());
        quote.setExpiresAt(Instant.now().plusSeconds(600));

        policy = new RentalPackagePolicy();
        policy.setFacility(facility);
        policy.setCode("THREE_MONTHS");
        policy.setPolicyVersion("2026-01");
        policy.setRentalMonths(3);
        policy.setDiscountRate(new BigDecimal("0.0300"));
        policy.setActive(true);
        policy.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    }

    @Test
    void createsReservationAndCopiesPricingSnapshot() {
        CreateReservationRequest request = request();
        quote.getFacility().setAccessHours("06:00 - 22:00 hàng ngày");
        quote.setFreeSetupDays(6);
        policy.setFreeSetupDays(6);
        request.setAppointmentAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(2, java.time.temporal.ChronoUnit.DAYS).plusSeconds(20 * 3600));
        when(reservationRepository.findByCustomer_IdAndIdempotencyKey(actor.userId(), "create-1"))
            .thenReturn(Optional.empty());
        when(quoteRepository.findByIdAndCustomer_Id(quote.getId(), actor.userId()))
            .thenReturn(Optional.of(quote));
        when(compatibilityService.checkQuotedSnapshot(any(), any())).thenReturn(compatibleResponse());
        when(policyRepository.findByFacility_IdAndCode(quote.getFacility().getId(), "THREE_MONTHS"))
            .thenReturn(Optional.of(policy));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            assertThat(reservation.getRequestFingerprint()).hasSize(64);
            assertThat(reservation.getIdentityDocumentNumber()).isEqualTo("079203009988");
            assertThat(reservation.getAppointmentAt()).isEqualTo(request.getAppointmentAt());
            var expectedStart = request.getAppointmentAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().plusDays(6);
            assertThat(reservation.getStartDate()).isEqualTo(expectedStart);
            assertThat(reservation.getEndDate()).isEqualTo(expectedStart.plusMonths(quote.getRentalMonths()));
            ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(reservation, "createdAt", Instant.now());
            return reservation;
        });
        when(snapshotRepository.saveAndFlush(any(ReservationPricingSnapshot.class))).thenAnswer(invocation -> {
            ReservationPricingSnapshot saved = invocation.getArgument(0);
            assertThat(saved.getSetupEarlyExitPolicyVersion()).isEqualTo(SetupEarlyExitPolicy.VERSION);
            assertThat(saved.getSetupEarlyExitFee()).isEqualByComparingTo("5500000");
            return saved;
        });

        var response = service.create(actor, request, "create-1");

        assertThat(response.getStatus()).isEqualTo(ReservationStatus.AWAITING_EMAIL);
        assertThat(response.getReservationDepositAmount()).isEqualByComparingTo("4301000.00");
        assertThat(response.getRemainingRentalAmount()).isEqualByComparingTo("11704000.00");
        assertThat(response.getDueAtCheckIn()).isEqualByComparingTo("17204000.00");
        assertThat(response.getSetupEarlyExitPolicyVersion()).isEqualTo(SetupEarlyExitPolicy.VERSION);
        assertThat(response.getSetupEarlyExitFee()).isEqualByComparingTo("5500000");
        var savedReservation=org.mockito.ArgumentCaptor.forClass(Reservation.class);
        var savedSnapshot=org.mockito.ArgumentCaptor.forClass(ReservationPricingSnapshot.class);
        org.mockito.Mockito.verify(reservationRepository).saveAndFlush(savedReservation.capture());
        org.mockito.Mockito.verify(snapshotRepository).saveAndFlush(savedSnapshot.capture());
        var captured=savedSnapshot.getValue();var originalSetup=captured.getFreeSetupDays();var originalPrice=captured.getMonthlyPrice();var originalVersion=captured.getPricingPolicyVersion();
        policy.setFreeSetupDays(99);policy.setPolicyVersion("TEST-NEW-POLICY");policy.setDiscountRate(new BigDecimal("0.9"));
        quote.getUnitType().setMonthlyPrice(new BigDecimal("99999999"));
        assertThat(ReservationSnapshotView.pricingComplete(savedReservation.getValue(),captured)).isTrue();
        assertThat(captured.getFreeSetupDays()).isEqualTo(originalSetup);
        assertThat(captured.getMonthlyPrice()).isEqualByComparingTo(originalPrice);
        assertThat(captured.getPricingPolicyVersion()).isEqualTo(originalVersion);
        assertThat(response.getReservationCode()).matches("RSV-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}");
    }

    @Test
    void rejectsStaleAndOutOfWindowAppointmentsWithActionableFieldDetails() {
        when(quoteRepository.findByIdAndCustomer_Id(quote.getId(), actor.userId()))
            .thenReturn(Optional.of(quote));
        when(compatibilityService.checkQuotedSnapshot(any(), any())).thenReturn(compatibleResponse());
        when(policyRepository.findByFacility_IdAndCode(quote.getFacility().getId(), "THREE_MONTHS"))
            .thenReturn(Optional.of(policy));
        for (long offsetSeconds : new long[] {-60, 6 * 3600, 9 * 24 * 3600}) {
            var input = request();
            input.setAppointmentAt(Instant.now().plusSeconds(offsetSeconds));
            assertThatThrownBy(() -> service.create(actor, input, "stale-appointment"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getDetails()).isEqualTo(Map.of("appointmentAt", error.getMessage()));
                    assertThat(error.getMessage()).contains("Lịch nhận kho").doesNotContain(";");
                });
        }
        org.mockito.Mockito.verify(reservationRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void missingTermsAcceptanceIdentifiesTheFieldWithoutCreatingAnOrder() {
        var input = request();
        input.setAcceptedTerms(false);
        assertThatThrownBy(() -> service.create(actor, input, "missing-terms"))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getDetails()).isEqualTo(Map.of("acceptedTerms", error.getMessage())));
        org.mockito.Mockito.verifyNoInteractions(reservationRepository, quoteRepository);
    }

    @Test
    void createsReservationWhenSecurityDepositDiffersFromMonthlyPrice() {
        // Cỡ kho có tiền cọc đảm bảo 200.000 khác đơn giá tháng 2.000.000
        quote.getUnitType().setMonthlyPrice(new BigDecimal("2000000.00"));
        quote.getUnitType().setSecurityDepositAmount(new BigDecimal("200000.00"));
        quote.setMonthlyPrice(new BigDecimal("2000000.00"));
        quote.setSetupEarlyExitFee(new BigDecimal("2000000.00"));
        quote.setSubtotal(new BigDecimal("6000000.00"));
        quote.setDiscountRate(new BigDecimal("0.0300"));
        quote.setDiscountAmount(new BigDecimal("180000.00"));
        quote.setTotalAfterDiscount(new BigDecimal("5820000.00"));
        quote.setReservationDepositAmount(new BigDecimal("1204000.00"));
        quote.setSecurityDepositAmount(new BigDecimal("200000.00"));
        quote.setRemainingRentalAmount(new BigDecimal("4616000.00"));
        quote.setDueAtCheckIn(new BigDecimal("4816000.00"));
        quote.setTotalInitialObligation(new BigDecimal("6020000.00"));

        CreateReservationRequest request = request();
        when(reservationRepository.findByCustomer_IdAndIdempotencyKey(actor.userId(), "create-diff-deposit"))
            .thenReturn(Optional.empty());
        when(quoteRepository.findByIdAndCustomer_Id(quote.getId(), actor.userId()))
            .thenReturn(Optional.of(quote));
        when(compatibilityService.checkQuotedSnapshot(any(), any())).thenReturn(compatibleResponse());
        when(policyRepository.findByFacility_IdAndCode(quote.getFacility().getId(), "THREE_MONTHS"))
            .thenReturn(Optional.of(policy));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(reservation, "createdAt", Instant.now());
            return reservation;
        });
        when(snapshotRepository.saveAndFlush(any(ReservationPricingSnapshot.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(actor, request, "create-diff-deposit");

        assertThat(response.getStatus()).isEqualTo(ReservationStatus.AWAITING_EMAIL);
        assertThat(response.getSecurityDepositAmount()).isEqualByComparingTo("200000.00");
    }

    @Test
    void rejectsExpiredQuote() {
        quote.setExpiresAt(Instant.now().minusSeconds(1));
        CreateReservationRequest request = request();
        when(reservationRepository.findByCustomer_IdAndIdempotencyKey(actor.userId(), "create-2"))
            .thenReturn(Optional.empty());
        when(quoteRepository.findByIdAndCustomer_Id(quote.getId(), actor.userId()))
            .thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> service.create(actor, request, "create-2"))
            .isInstanceOf(ApiException.class)
            .hasMessage("Quote has expired");
    }

    @Test
    void returnsExistingReservationWhenIdempotencyKeyIsRetried() {
        Reservation existing = new Reservation();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(existing, "createdAt", Instant.now());
        existing.setReservationCode("RSV-EXISTING001");
        existing.setCustomer(quote.getCustomer());
        existing.setIdentityDocumentNumber("079203009988");
        existing.setSourceQuote(quote);
        existing.setIdempotencyKey("create-retry");
        existing.setFacility(quote.getFacility());
        existing.setUnitType(quote.getUnitType());
        existing.setStatus(ReservationStatus.AWAITING_EMAIL);
        existing.setCompatibilityResult(CompatibilityResult.COMPATIBLE);
        existing.setStartDate(quote.getStartDate());
        existing.setEndDate(quote.getEndDate());
        existing.setTotalGoodsVolumeM3(new BigDecimal("0.180000"));
        existing.setTotalGoodsWeightKg(new BigDecimal("36.00"));
        existing.setHoldExpiresAt(Instant.now().plusSeconds(600));
        existing.setIdentityDocumentNumber(request().getIdentityDocumentNumber());
        existing.setCustomerPhone(request().getCustomerPhone());
        existing.setCustomerAddress(request().getCustomerAddress());
        existing.setGoodsCondition("Packed");
        existing.setNotes("Handle with care");

        ReservationGoodsItem storedItem = new ReservationGoodsItem();
        storedItem.setReservation(existing);
        storedItem.setCategory(GoodsCategory.FURNITURE);
        storedItem.setMaterialName("Wood");
        storedItem.setDescription("Desk");
        storedItem.setQuantity(2);
        storedItem.setLengthCm(new BigDecimal("100.00"));
        storedItem.setWidthCm(new BigDecimal("60.0"));
        storedItem.setHeightCm(new BigDecimal("15"));
        storedItem.setWeightKg(new BigDecimal("18.000"));

        ReservationPricingSnapshot snapshot = new ReservationPricingSnapshot();
        snapshot.setNetRentalAmount(quote.getTotalAfterDiscount());
        snapshot.setReservationDepositAmount(quote.getReservationDepositAmount());
        snapshot.setSecurityDepositAmount(quote.getSecurityDepositAmount());
        snapshot.setRemainingRentalAmount(quote.getRemainingRentalAmount());
        snapshot.setDueAtCheckIn(quote.getDueAtCheckIn());
        snapshot.setTotalInitialObligation(quote.getTotalInitialObligation());

        when(reservationRepository.findByCustomer_IdAndIdempotencyKey(actor.userId(), "create-retry"))
            .thenReturn(Optional.of(existing));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(existing.getId()))
            .thenReturn(List.of(storedItem));
        when(snapshotRepository.findByReservation_Id(existing.getId())).thenReturn(Optional.of(snapshot));

        var response = service.create(actor, request(), "create-retry");

        assertThat(response.getId()).isEqualTo(existing.getId());
        assertThat(response.getReservationCode()).isEqualTo("RSV-EXISTING001");
    }

    @Test
    void rejectsIdempotencyKeyWhenPayloadChanges() {
        Reservation existing = new Reservation();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setSourceQuote(quote);
        existing.setRequestFingerprint(ReservationCreationService.requestFingerprint(request()));

        when(reservationRepository.findByCustomer_IdAndIdempotencyKey(actor.userId(), "create-conflict"))
            .thenReturn(Optional.of(existing));

        CreateReservationRequest changed = request();
        changed.setNotes("Different notes");

        assertThatThrownBy(() -> service.create(actor, changed, "create-conflict"))
            .isInstanceOf(ApiException.class)
            .hasMessage("Idempotency-Key is already used for a different request");
    }

    @Test
    void normalizesWhitespaceAndDecimalScaleForIdempotentRetry() {
        CreateReservationRequest original = request();
        CreateReservationRequest equivalent = request();
        equivalent.setGoodsCondition("  Packed  ");
        equivalent.setNotes("  Handle with care  ");
        equivalent.getGoodsItems().get(0).setLengthCm(new BigDecimal("100.0"));

        assertThat(ReservationCreationService.requestFingerprint(equivalent))
            .isEqualTo(ReservationCreationService.requestFingerprint(original));
    }

    @Test
    void creationRequiresExplicitAcceptanceOfTheQuotedSetupPolicy() {
        var input = request();
        input.setAcceptedSetupEarlyExitPolicyVersion(null);
        when(quoteRepository.findByIdAndCustomer_Id(quote.getId(), actor.userId())).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.create(actor, input, "missing-setup-acceptance"))
            .hasMessageContaining("xác nhận điều khoản");
        input.setAcceptedSetupEarlyExitPolicyVersion("OTHER_VERSION");
        assertThatThrownBy(() -> service.create(actor, input, "wrong-setup-acceptance"))
            .hasMessageContaining("xác nhận điều khoản");
        org.mockito.Mockito.verify(reservationRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        org.mockito.Mockito.verifyNoInteractions(compatibilityService);
    }

    @Test
    void historicalQuoteCannotBeUpgradedBySendingTheCurrentPolicyCode() {
        var input = request();
        quote.setSetupEarlyExitPolicyVersion(null);
        quote.setSetupEarlyExitFee(null);
        when(quoteRepository.findByIdAndCustomer_Id(quote.getId(), actor.userId())).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.create(actor, input, "old-quote"))
            .hasMessageContaining("lấy báo giá mới");
        org.mockito.Mockito.verify(reservationRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void setupPolicyAcceptanceIsBoundToTheIdempotencyFingerprint() {
        var original = request();
        var changed = request();
        changed.setAcceptedSetupEarlyExitPolicyVersion("OTHER_VERSION");
        assertThat(ReservationCreationService.requestFingerprint(changed))
            .isNotEqualTo(ReservationCreationService.requestFingerprint(original));
    }

    private CreateReservationRequest request() {
        GoodsItemRequest item = new GoodsItemRequest(
            GoodsCategory.FURNITURE, null, "Wood", null, "Desk", null, 2,
            new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("15"),
            new BigDecimal("18"), false
        );
        CreateReservationRequest request = new CreateReservationRequest(
            quote.getId(), "Packed", "Handle with care", List.of(item)
        );
        request.setIdentityDocumentNumber("079203009988");
        request.setCustomerPhone("0908123456");
        request.setCustomerAddress("Quận 1, TP. Hồ Chí Minh");
        request.setAcceptedSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        quote.setGoodsSnapshotJson(GoodsSnapshotCodec.encode(List.of(item)));
        quote.setUnitTypeSnapshotJson("{\"name\":\"Kho tại lúc báo giá\"}");
        return request;
    }

    private CompatibilityCheckResponse compatibleResponse() {
        return new CompatibilityCheckResponse(
            quote.getFacility().getId(), quote.getUnitType().getId(), quote.getStartDate(),
            quote.getEndDate(), CompatibilityResult.COMPATIBLE, new BigDecimal("0.180000"),
            new BigDecimal("36.00"), new BigDecimal("15.000000"),
            new BigDecimal("1000.00"), new BigDecimal("0.80"),
            new BigDecimal("28.800000"), 1, 4, 2, false, List.of()
        );
    }
}
