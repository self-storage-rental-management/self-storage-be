package com.storagehub.service;

import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.CompatibilityCheckResponse;
import com.storagehub.api.reservation.CreateReservationRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.ReservationResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationQuoteRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationCreationService {

    private static final long HOLD_MINUTES = 10;
    private static final BigDecimal RESERVATION_DEPOSIT_RATE = new BigDecimal("0.40");
    private final ReservationRepository reservationRepository;
    private final ReservationQuoteRepository quoteRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final RentalPackagePolicyRepository policyRepository;
    private final ReservationCompatibilityService compatibilityService;
    private final AuditLogService auditLogService;
    private final ReservationCapacityService capacityService;

    @Transactional
    public ReservationResponse create(ActorPrincipal actor, CreateReservationRequest request,
                                      String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String requestFingerprint = requestFingerprint(request);
        Reservation existing = reservationRepository
            .findByCustomer_IdAndIdempotencyKey(actor.userId(), normalizedKey)
            .orElse(null);
        if (existing != null) {
            String existingFingerprint = existing.getRequestFingerprint();
            if (existingFingerprint == null) {
                existingFingerprint = requestFingerprint(existing,
                    goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(existing.getId()));
            }
            if (!MessageDigest.isEqual(existingFingerprint.getBytes(StandardCharsets.US_ASCII),
                                       requestFingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw ApiExceptions.conflict("Idempotency-Key is already used for a different request");
            }
            ReservationPricingSnapshot existingSnapshot = snapshotRepository
                .findByReservation_Id(existing.getId())
                .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
            return toResponse(existing, existingSnapshot);
        }

        ReservationQuote quote = quoteRepository.findByIdAndCustomer_Id(request.getQuoteId(), actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Quote was not found"));
        if (!quote.getExpiresAt().isAfter(Instant.now())) {
            throw ApiExceptions.conflict("Quote has expired");
        }

        CompatibilityCheckRequest compatibilityRequest = new CompatibilityCheckRequest(
            quote.getFacility().getId(), quote.getUnitType().getId(), quote.getStartDate(),
            quote.getEndDate(), request.getGoodsCondition(), request.getGoodsItems()
        );
        CompatibilityCheckResponse compatibility = compatibilityService.check(actor, compatibilityRequest);
        if (compatibility.getResult() == CompatibilityResult.INCOMPATIBLE) {
            throw ApiExceptions.conflict("Goods are not compatible with the selected unit type");
        }
        validateQuoteStillCurrent(quote);

        capacityService.lockAndRequireAvailableCapacity(
            quote.getFacility().getId(), quote.getUnitType().getId(),
            quote.getStartDate(), quote.getEndDate()
        );
        Instant now = Instant.now();
        boolean reviewRequired = compatibility.getResult() == CompatibilityResult.REVIEW_REQUIRED;
        Reservation reservation = new Reservation();
        reservation.setReservationCode(generateReservationCode());
        reservation.setCustomer(quote.getCustomer());
        reservation.setSourceQuote(quote);
        reservation.setIdempotencyKey(normalizedKey);
        reservation.setRequestFingerprint(requestFingerprint);
        reservation.setFacility(quote.getFacility());
        reservation.setUnitType(quote.getUnitType());
        reservation.setStatus(ReservationStatus.AWAITING_EMAIL);
        reservation.setGoodsReviewStatus(reviewRequired ? GoodsReviewStatus.PENDING : GoodsReviewStatus.NOT_REQUIRED);
        reservation.setCompatibilityResult(compatibility.getResult());
        reservation.setStartDate(quote.getStartDate());
        reservation.setEndDate(quote.getEndDate());
        reservation.setAmount(quote.getTotalAfterDiscount());
        reservation.setGoodsCondition(clean(request.getGoodsCondition()));
        reservation.setTotalGoodsVolumeM3(compatibility.getTotalGoodsVolumeM3());
        reservation.setTotalGoodsWeightKg(compatibility.getTotalGoodsWeightKg());
        reservation.setHoldExpiresAt(now.plus(HOLD_MINUTES, ChronoUnit.MINUTES));
        // The payment window starts only after email verification (and goods review, when required).
        reservation.setPaymentExpiresAt(null);
        reservation.setNotes(clean(request.getNotes()));
        Reservation saved = reservationRepository.saveAndFlush(reservation);

        List<ReservationGoodsItem> goodsItems = new ArrayList<>();
        for (GoodsItemRequest itemRequest : request.getGoodsItems()) {
            goodsItems.add(toGoodsItem(saved, itemRequest));
        }
        goodsItemRepository.saveAll(goodsItems);

        ReservationPricingSnapshot snapshot = toSnapshot(saved, quote);
        ReservationPricingSnapshot savedSnapshot = snapshotRepository.saveAndFlush(snapshot);
        ReservationResponse response = toResponse(saved, savedSnapshot);
        auditLogService.recordMutation(
            quote.getCustomer(), "RESERVATION_CREATED", "Reservation", saved.getId(),
            null, null, response
        );
        return response;
    }

    private void validateQuoteStillCurrent(ReservationQuote quote) {
        RentalPackagePolicy policy = policyRepository
            .findByFacility_IdAndCode(quote.getFacility().getId(), quote.getPricingPackageCode())
            .orElseThrow(() -> ApiExceptions.conflict("Rental package is no longer available"));
        BigDecimal currentMonthlyPrice = money(quote.getUnitType().getMonthlyPrice());
        BigDecimal subtotal = money(currentMonthlyPrice.multiply(BigDecimal.valueOf(quote.getRentalMonths())));
        BigDecimal discountAmount = money(subtotal.multiply(policy.getDiscountRate()));
        BigDecimal totalAfterDiscount = money(subtotal.subtract(discountAmount));
        BigDecimal reservationDeposit = money(totalAfterDiscount.multiply(RESERVATION_DEPOSIT_RATE));
        BigDecimal securityDeposit = currentMonthlyPrice;

        boolean policyInvalid = !policy.isActive()
            || !policy.getPolicyVersion().equals(quote.getPolicyVersion())
            || policy.getRentalMonths() != quote.getRentalMonths()
            || policy.getEffectiveFrom().isAfter(quote.getStartDate())
            || (policy.getEffectiveTo() != null && policy.getEffectiveTo().isBefore(quote.getStartDate()));
        boolean priceChanged = currentMonthlyPrice.compareTo(quote.getMonthlyPrice()) != 0
            || subtotal.compareTo(quote.getSubtotal()) != 0
            || policy.getDiscountRate().compareTo(quote.getDiscountRate()) != 0
            || discountAmount.compareTo(quote.getDiscountAmount()) != 0
            || totalAfterDiscount.compareTo(quote.getTotalAfterDiscount()) != 0
            || reservationDeposit.compareTo(quote.getReservationDepositAmount()) != 0
            || securityDeposit.compareTo(quote.getSecurityDepositAmount()) != 0;
        if (policyInvalid || priceChanged) {
            throw ApiExceptions.conflict("Quote is no longer valid because pricing has changed");
        }
    }

    private ReservationGoodsItem toGoodsItem(Reservation reservation, GoodsItemRequest request) {
        boolean reviewRequired = request.getCategory() == GoodsCategory.OTHER;
        ReservationGoodsItem item = new ReservationGoodsItem();
        item.setReservation(reservation);
        item.setCategory(request.getCategory());
        item.setCustomGoodsName(clean(request.getCustomGoodsName()));
        item.setMaterialName(clean(request.getMaterialName()));
        item.setCustomMaterial(clean(request.getCustomMaterial()));
        item.setDescription(clean(request.getDescription()));
        item.setQuantity(request.getQuantity());
        item.setLengthCm(request.getLengthCm());
        item.setWidthCm(request.getWidthCm());
        item.setHeightCm(request.getHeightCm());
        item.setWeightKg(request.getWeightPerItemKg());
        item.setFragile(request.isFragile());
        item.setCustomerNote(clean(request.getCustomerNote()));
        item.setRequiresStaffReview(reviewRequired);
        item.setReviewStatus(reviewRequired ? GoodsReviewStatus.PENDING : GoodsReviewStatus.NOT_REQUIRED);
        return item;
    }

    private ReservationPricingSnapshot toSnapshot(Reservation reservation, ReservationQuote quote) {
        ReservationPricingSnapshot snapshot = new ReservationPricingSnapshot();
        snapshot.setReservation(reservation);
        snapshot.setPricingPackageCode(quote.getPricingPackageCode());
        snapshot.setPricingPolicyVersion(quote.getPolicyVersion());
        snapshot.setRentalMonths(quote.getRentalMonths());
        snapshot.setMonthlyPrice(quote.getMonthlyPrice());
        snapshot.setGrossRentalAmount(quote.getSubtotal());
        snapshot.setDiscountRate(quote.getDiscountRate());
        snapshot.setDiscountAmount(quote.getDiscountAmount());
        snapshot.setNetRentalAmount(quote.getTotalAfterDiscount());
        snapshot.setReservationDepositAmount(quote.getReservationDepositAmount());
        snapshot.setSecurityDepositAmount(quote.getSecurityDepositAmount());
        snapshot.setRemainingRentalAmount(quote.getRemainingRentalAmount());
        snapshot.setDueAtCheckIn(quote.getDueAtCheckIn());
        snapshot.setTotalInitialObligation(quote.getTotalInitialObligation());
        snapshot.setQuotedAt(quote.getQuotedAt());
        snapshot.setExpiresAt(quote.getExpiresAt());
        return snapshot;
    }

    private ReservationResponse toResponse(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return new ReservationResponse(
            reservation.getId(), reservation.getReservationCode(), reservation.getSourceQuote().getId(),
            reservation.getFacility().getId(), reservation.getUnitType().getId(), reservation.getStatus(),
            reservation.getGoodsReviewStatus(), reservation.getCompatibilityResult(),
            reservation.getStartDate(), reservation.getEndDate(), snapshot.getNetRentalAmount(),
            snapshot.getReservationDepositAmount(), snapshot.getSecurityDepositAmount(),
            snapshot.getRemainingRentalAmount(), snapshot.getDueAtCheckIn(),
            snapshot.getTotalInitialObligation(), reservation.getTotalGoodsVolumeM3(),
            reservation.getTotalGoodsWeightKg(), reservation.getHoldExpiresAt(), reservation.getCreatedAt(),
            snapshot.getPricingPackageCode(), snapshot.getRentalMonths(), snapshot.getGrossRentalAmount(),
            snapshot.getDiscountRate(), snapshot.getDiscountAmount()
        );
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw ApiExceptions.validation("Idempotency-Key is required and must be at most 100 characters", null);
        }
        return value.trim();
    }

    private String generateReservationCode() {
        return "RSV-" + UUID.randomUUID().toString().replace("-", "")
            .substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String requestFingerprint(CreateReservationRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.getQuoteId());
        append(canonical, cleanValue(request.getGoodsCondition()));
        append(canonical, cleanValue(request.getNotes()));
        List<GoodsItemRequest> items = request.getGoodsItems();
        append(canonical, items == null ? null : items.size());
        if (items != null) {
            for (GoodsItemRequest item : items) {
                append(canonical, item.getCategory());
                append(canonical, cleanValue(item.getCustomGoodsName()));
                append(canonical, cleanValue(item.getMaterialName()));
                append(canonical, cleanValue(item.getCustomMaterial()));
                append(canonical, cleanValue(item.getDescription()));
                append(canonical, cleanValue(item.getCustomerNote()));
                append(canonical, item.getQuantity());
                append(canonical, decimal(item.getLengthCm()));
                append(canonical, decimal(item.getWidthCm()));
                append(canonical, decimal(item.getHeightCm()));
                append(canonical, decimal(item.getWeightPerItemKg()));
                append(canonical, item.isFragile());
            }
        }
        return sha256(canonical.toString());
    }

    private static String requestFingerprint(Reservation reservation, List<ReservationGoodsItem> items) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, reservation.getSourceQuote().getId());
        append(canonical, cleanValue(reservation.getGoodsCondition()));
        append(canonical, cleanValue(reservation.getNotes()));
        append(canonical, items.size());
        for (ReservationGoodsItem item : items) {
            append(canonical, item.getCategory());
            append(canonical, cleanValue(item.getCustomGoodsName()));
            append(canonical, cleanValue(item.getMaterialName()));
            append(canonical, cleanValue(item.getCustomMaterial()));
            append(canonical, cleanValue(item.getDescription()));
            append(canonical, cleanValue(item.getCustomerNote()));
            append(canonical, item.getQuantity());
            append(canonical, decimal(item.getLengthCm()));
            append(canonical, decimal(item.getWidthCm()));
            append(canonical, decimal(item.getHeightCm()));
            append(canonical, decimal(item.getWeightKg()));
            append(canonical, item.isFragile());
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }

    private static String cleanValue(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
