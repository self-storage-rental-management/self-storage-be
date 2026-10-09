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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationQuoteService {

    private static final BigDecimal RESERVATION_DEPOSIT_RATE = new BigDecimal("0.40");
    private static final long QUOTE_VALID_MINUTES = 15;

    private final ReservationCompatibilityService compatibilityService;
    private final RentalPackagePolicyRepository policyRepository;
    private final ReservationQuoteRepository quoteRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final UserRepository userRepository;

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
        return policyRepository
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
                facilityId,
                effectiveDate
            )
            .stream()
            .filter(policy -> policy.getEffectiveTo() == null
                || !policy.getEffectiveTo().isBefore(effectiveDate))
            .map(policy -> new ReservationRentalPackageResponse(
                policy.getCode(),
                policy.getName(),
                policy.getRentalMonths(),
                policy.getDiscountRate(),
                policy.getPolicyVersion()
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
        RentalPackagePolicy policy = policyRepository
            .findByFacility_IdAndCode(request.getFacilityId(), request.getPricingPackageCode().trim())
            .orElseThrow(() -> ApiExceptions.notFound("Rental package was not found"));
        validatePolicy(policy, request.getStartDate(), rentalMonths);

        UnitType unitType = unitTypeRepository.findById(request.getUnitTypeId())
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        User customer = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));

        BigDecimal monthlyPrice = money(unitType.getMonthlyPrice());
        BigDecimal subtotal = money(monthlyPrice.multiply(BigDecimal.valueOf(rentalMonths)));
        BigDecimal discountAmount = money(subtotal.multiply(policy.getDiscountRate()));
        BigDecimal totalAfterDiscount = money(subtotal.subtract(discountAmount));
        BigDecimal reservationDepositAmount = money(totalAfterDiscount.multiply(RESERVATION_DEPOSIT_RATE));
        BigDecimal securityDepositAmount = monthlyPrice;
        BigDecimal remainingRentalAmount = money(totalAfterDiscount.subtract(reservationDepositAmount));
        BigDecimal dueAtCheckIn = money(remainingRentalAmount.add(securityDepositAmount));
        BigDecimal totalInitialObligation = money(totalAfterDiscount.add(securityDepositAmount));
        Instant quotedAt = Instant.now();

        ReservationQuote quote = new ReservationQuote();
        quote.setCustomer(customer);
        quote.setFacility(policy.getFacility());
        quote.setUnitType(unitType);
        quote.setPricingPackageCode(policy.getCode());
        quote.setPolicyVersion(policy.getPolicyVersion());
        quote.setStartDate(request.getStartDate());
        quote.setEndDate(request.getEndDate());
        quote.setRentalMonths(rentalMonths);
        quote.setMonthlyPrice(monthlyPrice);
        quote.setSubtotal(subtotal);
        quote.setDiscountRate(policy.getDiscountRate());
        quote.setDiscountAmount(discountAmount);
        quote.setTotalAfterDiscount(totalAfterDiscount);
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

    private int calculateRentalMonths(LocalDate startDate, LocalDate endDate) {
        long months = ChronoUnit.MONTHS.between(startDate, endDate);
        if (months < 1 || months > 120 || !startDate.plusMonths(months).equals(endDate)) {
            throw ApiExceptions.validation("Rental period must use complete months", null);
        }
        return Math.toIntExact(months);
    }

    private void validatePolicy(RentalPackagePolicy policy, LocalDate startDate, int rentalMonths) {
        if (!policy.isActive()
            || policy.getEffectiveFrom().isAfter(startDate)
            || (policy.getEffectiveTo() != null && policy.getEffectiveTo().isBefore(startDate))) {
            throw ApiExceptions.conflict("Rental package is not effective for the selected start date");
        }
        if (policy.getRentalMonths() != rentalMonths) {
            throw ApiExceptions.validation("Rental period does not match the selected package", null);
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private ReservationQuoteResponse toResponse(ReservationQuote quote) {
        return new ReservationQuoteResponse(
            quote.getId(), quote.getFacility().getId(), quote.getUnitType().getId(),
            quote.getStartDate(), quote.getEndDate(), quote.getRentalMonths(),
            quote.getMonthlyPrice(), quote.getSubtotal(), quote.getPricingPackageCode(),
            quote.getDiscountRate(), quote.getDiscountAmount(), quote.getTotalAfterDiscount(),
            quote.getReservationDepositAmount(), quote.getSecurityDepositAmount(),
            quote.getRemainingRentalAmount(), quote.getDueAtCheckIn(),
            quote.getTotalInitialObligation(),
            quote.getPolicyVersion(), quote.getQuotedAt(), quote.getExpiresAt()
        );
    }
}
