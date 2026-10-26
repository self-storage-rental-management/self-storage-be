package com.storagehub.service;

import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.CompatibilityCheckResponse;
import com.storagehub.api.reservation.ReservationQuoteRequest;
import com.storagehub.api.reservation.ReservationQuoteResponse;
import com.storagehub.api.reservation.ReservationRentalPackageResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.ReservationQuoteRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationQuoteService {

    private static final BigDecimal RESERVATION_DEPOSIT_RATE = RentalDepositPolicy.RATE;
    private static final long QUOTE_VALID_MINUTES = 15;

    private final ReservationCompatibilityService compatibilityService;
    private final RentalPackagePolicyRepository policyRepository;
    private final ReservationQuoteRepository quoteRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final UserRepository userRepository;
    private final DiscountCodeService discountCodeService;

    @org.springframework.beans.factory.annotation.Autowired
    public ReservationQuoteService(
        ReservationCompatibilityService compatibilityService,
        RentalPackagePolicyRepository policyRepository,
        ReservationQuoteRepository quoteRepository,
        UnitTypeRepository unitTypeRepository,
        UserRepository userRepository,
        DiscountCodeService discountCodeService
    ) {
        this.compatibilityService = compatibilityService;
        this.policyRepository = policyRepository;
        this.quoteRepository = quoteRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.userRepository = userRepository;
        this.discountCodeService = discountCodeService;
    }

    public ReservationQuoteService(
        ReservationCompatibilityService compatibilityService,
        RentalPackagePolicyRepository policyRepository,
        ReservationQuoteRepository quoteRepository,
        UnitTypeRepository unitTypeRepository,
        UserRepository userRepository
    ) {
        this(compatibilityService, policyRepository, quoteRepository, unitTypeRepository, userRepository, null);
    }

    @Transactional(readOnly = true)
    public List<ReservationRentalPackageResponse> listAvailablePackages(
        ActorPrincipal actor,
        java.util.UUID facilityId,
        LocalDate startDate
    ) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customers can read reservation rental packages");
        }
        LocalDate effectiveDate = startDate == null ? LocalDate.now() : startDate;
        List<RentalPackagePolicy> effective = policyRepository
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
                facilityId,
                effectiveDate
            )
            .stream()
            .filter(policy -> policy.getEffectiveTo() == null
                || !policy.getEffectiveTo().isBefore(effectiveDate))
            .toList();

        // Defensive cleanup for legacy rows created before the uniqueness rule.
        // Prefer the policy with the newest effective start for each duration so
        // Customer can never receive two choices for the same number of months.
        var uniqueByMonths = new LinkedHashMap<Integer, RentalPackagePolicy>();
        effective.stream()
            .sorted(java.util.Comparator.comparing(RentalPackagePolicy::getEffectiveFrom).reversed()
                .thenComparing(RentalPackagePolicy::getUpdatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .forEach(policy -> uniqueByMonths.putIfAbsent(policy.getRentalMonths(), policy));
        return uniqueByMonths.values().stream()
            .sorted(java.util.Comparator.comparingInt(RentalPackagePolicy::getRentalMonths))
            .map(policy -> new ReservationRentalPackageResponse(
                policy.getCode(),
                policy.getName(),
                policy.getRentalMonths(),
                policy.getFreeSetupDays(),
                policy.getDiscountRate(),
                policy.getPolicyVersion(),
                policy.getDepositRate() != null ? policy.getDepositRate() : RESERVATION_DEPOSIT_RATE
            ))
            .toList();
    }

    @Transactional
    public ReservationQuoteResponse createQuote(ActorPrincipal actor, ReservationQuoteRequest request) {
        CompatibilityCheckRequest compatibilityRequest = new CompatibilityCheckRequest(
            request.getFacilityId(), request.getUnitTypeId(), request.getStartDate(),
            request.getEndDate(), request.getGoodsCondition(), request.getGoodsItems()
        );
        CompatibilityCheckResponse compatibility = compatibilityService.check(actor, compatibilityRequest);
        if (compatibility.getResult() == CompatibilityResult.INCOMPATIBLE) {
            throw ApiExceptions.conflict("Goods are not compatible with the selected unit type");
        }

        int rentalMonths = calculateRentalMonths(request.getStartDate(), request.getEndDate());

        UnitType unitType = unitTypeRepository.findById(request.getUnitTypeId())
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        if (!unitType.getFacility().getId().equals(request.getFacilityId())) {
            throw ApiExceptions.validation("Unit type does not belong to the selected facility", null);
        }
        User customer = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));

        RentalPackagePolicy policy = policyRepository
            .findByFacility_IdAndCode(request.getFacilityId(), request.getPricingPackageCode().trim())
            .orElseThrow(() -> ApiExceptions.notFound("Rental package was not found"));
        validatePolicy(policy, request.getStartDate(), request.getEndDate(), rentalMonths);

        BigDecimal monthlyPrice = money(unitType.getMonthlyPrice());
        BigDecimal subtotal = money(monthlyPrice.multiply(BigDecimal.valueOf(rentalMonths)));
        BigDecimal discountAmount = money(subtotal.multiply(policy.getDiscountRate()));
        BigDecimal baseEligibleAmount = money(subtotal.subtract(discountAmount));

        BigDecimal voucherDiscountAmount = BigDecimal.ZERO;
        String appliedVoucherCode = null;
        if (discountCodeService != null && request.getVoucherCode() != null && !request.getVoucherCode().isBlank()) {
            var voucherResult = discountCodeService.calculateVoucherDiscount(
                request.getVoucherCode(),
                policy.getFacility().getId(),
                policy.getId(),
                baseEligibleAmount
            );
            if (voucherResult != null) {
                voucherDiscountAmount = money(voucherResult.discountAmount());
                appliedVoucherCode = voucherResult.voucherCode();
            }
        }

        BigDecimal totalAfterDiscount = money(baseEligibleAmount.subtract(voucherDiscountAmount));
        BigDecimal depositRate = policy.getDepositRate() != null ? policy.getDepositRate() : RESERVATION_DEPOSIT_RATE;
        BigDecimal reservationDepositAmount = money(totalAfterDiscount.multiply(depositRate));
        int earlyTerminationFeeCapMonths = policy.getEarlyTerminationFeeCapMonths() > 0
            ? policy.getEarlyTerminationFeeCapMonths() : EarlyTerminationPolicy.DEFAULT_FEE_CAP_MONTHS;
        BigDecimal earlyTerminationFeeCoefficient = policy.getEarlyTerminationFeeCoefficient() != null
            ? policy.getEarlyTerminationFeeCoefficient() : EarlyTerminationPolicy.DEFAULT_FEE_COEFFICIENT;
        int earlyTerminationNoticeDays = policy.getEarlyTerminationNoticeDays() >= 0
            ? policy.getEarlyTerminationNoticeDays() : EarlyTerminationPolicy.DEFAULT_NOTICE_DAYS;
        BigDecimal earlyTerminationFee = EarlyTerminationPolicy.fee(
            reservationDepositAmount, totalAfterDiscount, rentalMonths,
            earlyTerminationFeeCoefficient, earlyTerminationFeeCapMonths
        );
        BigDecimal securityDepositAmount = money(
            (unitType.getSecurityDepositAmount() == null || unitType.getSecurityDepositAmount().compareTo(BigDecimal.ZERO) <= 0)
                ? monthlyPrice
                : unitType.getSecurityDepositAmount()
        );
        BigDecimal remainingRentalAmount = money(totalAfterDiscount.subtract(reservationDepositAmount));
        BigDecimal dueAtCheckIn = money(remainingRentalAmount.add(securityDepositAmount));
        BigDecimal totalInitialObligation = money(totalAfterDiscount.add(securityDepositAmount));
        Instant quotedAt = Instant.now();

        ReservationQuote quote = new ReservationQuote();
        quote.setCustomer(customer);
        quote.setGoodsSnapshotJson(GoodsSnapshotCodec.encode(request.getGoodsItems()));
        quote.setUnitTypeRevision(unitType.getPublishedRevision());
        var unitSnapshot = new java.util.LinkedHashMap<String, Object>();
        unitSnapshot.put("code", unitType.getCode());
        unitSnapshot.put("name", unitType.getName());
        unitSnapshot.put("description", unitType.getDescription());
        unitSnapshot.put("lengthM", unitType.getLengthM());
        unitSnapshot.put("widthM", unitType.getWidthM());
        unitSnapshot.put("heightM", unitType.getHeightM());
        unitSnapshot.put("maxLoadKg", unitType.getMaxLoadKg());
        unitSnapshot.put("rackCount", unitType.getRackCount());
        unitSnapshot.put("rackLengthM", unitType.getRackLengthM());
        unitSnapshot.put("rackWidthM", unitType.getRackWidthM());
        unitSnapshot.put("rackHeightM", unitType.getRackHeightM());
        unitSnapshot.put("monthlyPrice", unitType.getMonthlyPrice());
        unitSnapshot.put("securityDepositAmount", unitType.getSecurityDepositAmount());
        unitSnapshot.put("publishedRevision", unitType.getPublishedRevision());
        unitSnapshot.put("acceptedGoodsJson", unitType.getAcceptedGoodsJson());
        quote.setUnitTypeSnapshotJson(GoodsSnapshotCodec.encodeObject(unitSnapshot));
        quote.setFacility(policy.getFacility());
        quote.setUnitType(unitType);
        quote.setPricingPackageCode(policy.getCode());
        quote.setPolicyVersion(policy.getPolicyVersion());
        quote.setStartDate(request.getStartDate());
        quote.setEndDate(request.getEndDate());
        quote.setRentalMonths(rentalMonths);
        quote.setFreeSetupDays(policy.getFreeSetupDays());
        quote.setMonthlyPrice(monthlyPrice);
        quote.setSubtotal(subtotal);
        quote.setDiscountRate(policy.getDiscountRate());
        quote.setDiscountAmount(discountAmount);
        quote.setVoucherCode(appliedVoucherCode);
        quote.setVoucherDiscountAmount(voucherDiscountAmount);
        quote.setTotalAfterDiscount(totalAfterDiscount);
        quote.setReservationDepositRate(depositRate);
        quote.setEarlyTerminationFeeCapMonths(earlyTerminationFeeCapMonths);
        quote.setEarlyTerminationFeeCoefficient(earlyTerminationFeeCoefficient);
        quote.setEarlyTerminationNoticeDays(earlyTerminationNoticeDays);
        quote.setEarlyTerminationFee(earlyTerminationFee);
        quote.setEarlyTerminationPricingSnapshotJson(
            createEarlyTerminationPricingSnapshot(policy, unitType, request.getStartDate())
        );
        quote.setReservationDepositAmount(reservationDepositAmount);
        quote.setSecurityDepositAmount(securityDepositAmount);
        quote.setRemainingRentalAmount(remainingRentalAmount);
        quote.setDueAtCheckIn(dueAtCheckIn);
        quote.setTotalInitialObligation(totalInitialObligation);
        quote.setQuotedAt(quotedAt);
        quote.setExpiresAt(quotedAt.plus(QUOTE_VALID_MINUTES, ChronoUnit.MINUTES));

        ReservationQuote saved = quoteRepository.saveAndFlush(quote);
        return toResponse(saved);
    }

    public static int calculateRentalMonths(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw ApiExceptions.validation("startDate and endDate are required", null);
        }
        if (!endDate.isAfter(startDate)) {
            throw ApiExceptions.validation("Rental period must use complete months", null);
        }
        for (int m = 1; m <= 120; m++) {
            LocalDate targetExclusive = startDate.plusMonths(m);
            LocalDate targetInclusive = targetExclusive.minusDays(1);
            if (targetInclusive.equals(endDate) || targetExclusive.equals(endDate)) {
                return m;
            }
            if (startDate.getDayOfMonth() == startDate.lengthOfMonth()
                && endDate.getDayOfMonth() == endDate.lengthOfMonth()
                && ChronoUnit.MONTHS.between(startDate, endDate) == m) {
                return m;
            }
        }
        throw ApiExceptions.validation("Rental period must use complete months", null);
    }

    private void validatePolicy(RentalPackagePolicy policy, LocalDate startDate, LocalDate endDate, int rentalMonths) {
        if (!policy.isActive()
            || policy.getEffectiveFrom().isAfter(startDate)
            || (policy.getEffectiveTo() != null && policy.getEffectiveTo().isBefore(endDate))) {
            throw ApiExceptions.conflict("Rental package is not effective for the whole selected rental period");
        }
        if (policy.getRentalMonths() != rentalMonths) {
            throw ApiExceptions.validation("Rental period does not match the selected package", null);
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private ReservationQuoteResponse toResponse(ReservationQuote quote) {
        ReservationQuoteResponse response = new ReservationQuoteResponse(
            quote.getId(),
            quote.getFacility().getId(), quote.getFacility().getName(),
            quote.getUnitType().getId(), quote.getUnitType().getName(),
            quote.getStartDate(), quote.getEndDate(), quote.getRentalMonths(),
            quote.getMonthlyPrice(), quote.getSubtotal(), quote.getPricingPackageCode(),
            quote.getDiscountRate(), quote.getDiscountAmount(),
            quote.getVoucherCode(), quote.getVoucherDiscountAmount(),
            quote.getTotalAfterDiscount(),
            quote.getReservationDepositAmount(), quote.getSecurityDepositAmount(),
            quote.getRemainingRentalAmount(), quote.getDueAtCheckIn(),
            quote.getTotalInitialObligation(),
            quote.getPolicyVersion(), quote.getQuotedAt(), quote.getExpiresAt()
        );
        response.setReservationDepositRate(
            quote.getReservationDepositRate() != null ? quote.getReservationDepositRate() : RESERVATION_DEPOSIT_RATE
        );
        response.setFreeSetupDays(quote.getFreeSetupDays());
        response.setEarlyTerminationFeeCapMonths(quote.getEarlyTerminationFeeCapMonths());
        response.setEarlyTerminationFeeCoefficient(quote.getEarlyTerminationFeeCoefficient());
        response.setEarlyTerminationNoticeDays(quote.getEarlyTerminationNoticeDays());
        response.setEarlyTerminationFee(quote.getEarlyTerminationFee());
        response.setEarlyTerminationNoticeBasis(EarlyTerminationPolicy.NOTICE_BASIS);
        response.setEarlyTerminationTiers(readPricingTiers(quote.getEarlyTerminationPricingSnapshotJson()));
        return response;
    }

    private List<ReservationQuoteResponse.EarlyTerminationTier> readPricingTiers(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            List<ReservationQuoteResponse.EarlyTerminationTier> result = new java.util.ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    result.add(new ReservationQuoteResponse.EarlyTerminationTier(
                        node.path("rentalMonths").asInt(),
                        node.path("monthlyPrice").decimalValue(),
                        node.path("freeSetupDays").asInt()
                    ));
                }
            }
            return result.stream().filter(item -> item.rentalMonths() > 0).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static int defaultFreeSetupDays(int rentalMonths) {
        return switch (rentalMonths) {
            case 3 -> 3;
            case 6 -> 7;
            case 12 -> 15;
            case 24 -> 30;
            default -> 0;
        };
    }

    private String createEarlyTerminationPricingSnapshot(
        RentalPackagePolicy selectedPolicy,
        UnitType unitType,
        LocalDate effectiveDate
    ) {
        BigDecimal listMonthlyPrice = money(unitType.getMonthlyPrice());
        var tiers = new LinkedHashMap<Integer, Map<String, Object>>();
        policyRepository
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
                selectedPolicy.getFacility().getId(), effectiveDate
            )
            .stream()
            .filter(policy -> policy.getEffectiveTo() == null || !policy.getEffectiveTo().isBefore(effectiveDate))
            .sorted(Comparator.comparing(RentalPackagePolicy::getEffectiveFrom).reversed())
            .forEach(policy -> tiers.putIfAbsent(policy.getRentalMonths(), pricingTier(policy, listMonthlyPrice)));
        tiers.putIfAbsent(selectedPolicy.getRentalMonths(), pricingTier(selectedPolicy, listMonthlyPrice));
        return GoodsSnapshotCodec.encodeObject(tiers.values());
    }

    private Map<String, Object> pricingTier(RentalPackagePolicy policy, BigDecimal listMonthlyPrice) {
        BigDecimal discount = policy.getDiscountRate() == null ? BigDecimal.ZERO : policy.getDiscountRate();
        BigDecimal packageMonthlyPrice = listMonthlyPrice
            .multiply(BigDecimal.ONE.subtract(discount))
            .setScale(2, RoundingMode.HALF_UP);
        return Map.of(
            "rentalMonths", policy.getRentalMonths(),
            "monthlyPrice", packageMonthlyPrice,
            "freeSetupDays", policy.getFreeSetupDays(),
            "pricingPackageCode", policy.getCode(),
            "policyVersion", policy.getPolicyVersion()
        );
    }
}
