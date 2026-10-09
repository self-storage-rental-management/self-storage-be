package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.CompatibilityCheckResponse;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.ReservationQuoteRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.ReservationQuoteRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
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
class ReservationQuoteServiceTests {

    @Mock private ReservationCompatibilityService compatibilityService;
    @Mock private RentalPackagePolicyRepository policyRepository;
    @Mock private ReservationQuoteRepository quoteRepository;
    @Mock private UnitTypeRepository unitTypeRepository;
    @Mock private UserRepository userRepository;

    private ReservationQuoteService service;
    private Facility facility;
    private UnitType unitType;
    private RentalPackagePolicy policy;
    private User customer;
    private ActorPrincipal actor;

    @BeforeEach
    void setUp() {
        service = new ReservationQuoteService(
            compatibilityService, policyRepository, quoteRepository, unitTypeRepository, userRepository
        );

        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());

        unitType = new UnitType();
        ReflectionTestUtils.setField(unitType, "id", UUID.randomUUID());
        unitType.setFacility(facility);
        unitType.setMonthlyPrice(new BigDecimal("5500000"));

        policy = new RentalPackagePolicy();
        policy.setFacility(facility);
        policy.setCode("PKG-3M");
        policy.setPolicyVersion("2026-01");
        policy.setRentalMonths(3);
        policy.setDiscountRate(new BigDecimal("0.0300"));
        policy.setActive(true);
        policy.setEffectiveFrom(LocalDate.of(2026, 1, 1));

        customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        actor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );
    }

    @Test
    void calculatesAndStoresQuoteUsingServerPrices() {
        ReservationQuoteRequest request = request(LocalDate.of(2026, 10, 10), LocalDate.of(2027, 1, 10));
        stubCompatible(request);
        when(policyRepository.findByFacility_IdAndCode(facility.getId(), "PKG-3M"))
            .thenReturn(Optional.of(policy));
        when(unitTypeRepository.findById(unitType.getId())).thenReturn(Optional.of(unitType));
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(quoteRepository.saveAndFlush(any(ReservationQuote.class))).thenAnswer(invocation -> {
            ReservationQuote quote = invocation.getArgument(0);
            ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
            return quote;
        });

        var response = service.createQuote(actor, request);

        assertThat(response.getRentalMonths()).isEqualTo(3);
        assertThat(response.getSubtotal()).isEqualByComparingTo("16500000.00");
        assertThat(response.getDiscountAmount()).isEqualByComparingTo("495000.00");
        assertThat(response.getTotalAfterDiscount()).isEqualByComparingTo("16005000.00");
        assertThat(response.getReservationDepositAmount()).isEqualByComparingTo("6402000.00");
        assertThat(response.getSecurityDepositAmount()).isEqualByComparingTo("5500000.00");
        assertThat(response.getRemainingRentalAmount()).isEqualByComparingTo("9603000.00");
        assertThat(response.getDueAtCheckIn()).isEqualByComparingTo("15103000.00");
        assertThat(response.getTotalInitialObligation()).isEqualByComparingTo("21505000.00");
        assertThat(response.getQuoteId()).isNotNull();
    }

    @Test
    void listsEffectivePackagesUsingTheirPersistedCodes() {
        LocalDate startDate = LocalDate.of(2026, 10, 10);
        when(policyRepository
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
                facility.getId(), startDate
            ))
            .thenReturn(List.of(policy));

        var packages = service.listAvailablePackages(actor, facility.getId(), startDate);

        assertThat(packages).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("PKG-3M");
            assertThat(item.rentalMonths()).isEqualTo(3);
        });
    }

    @Test
    void rejectsRentalPeriodThatIsNotCompleteMonths() {
        ReservationQuoteRequest request = request(LocalDate.of(2026, 10, 10), LocalDate.of(2027, 1, 9));
        stubCompatible(request);

        assertThatThrownBy(() -> service.createQuote(actor, request))
            .isInstanceOf(ApiException.class)
            .hasMessage("Rental period must use complete months");
    }

    private void stubCompatible(ReservationQuoteRequest request) {
        CompatibilityCheckResponse response = new CompatibilityCheckResponse(
            facility.getId(), unitType.getId(), request.getStartDate(), request.getEndDate(),
            CompatibilityResult.COMPATIBLE, new BigDecimal("0.18"), new BigDecimal("36"),
            new BigDecimal("15"), new BigDecimal("1000"), new BigDecimal("0.80"),
            new BigDecimal("28.8"), 1, 4, 2, false, List.of()
        );
        when(compatibilityService.check(any(), any())).thenReturn(response);
    }

    private ReservationQuoteRequest request(LocalDate startDate, LocalDate endDate) {
        GoodsItemRequest item = new GoodsItemRequest(
            GoodsCategory.FURNITURE, null, "Wood", null, "Desk", null, 2,
            new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("15"),
            new BigDecimal("18"), false
        );
        return new ReservationQuoteRequest(
            facility.getId(), unitType.getId(), "PKG-3M", startDate, endDate,
            "Packed", List.of(item)
        );
    }
}
