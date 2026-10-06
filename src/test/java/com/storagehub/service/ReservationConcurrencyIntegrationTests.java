package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.api.reservation.CreateReservationRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.ReservationQuoteRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.bootstrap-admin.enabled=false")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationConcurrencyIntegrationTests {

    @Autowired private ReservationCreationService creationService;
    @Autowired private ReservationCapacityService capacityService;
    @Autowired private FacilityRepository facilityRepository;
    @Autowired private UnitTypeRepository unitTypeRepository;
    @Autowired private StorageUnitRepository storageUnitRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RentalPackagePolicyRepository policyRepository;
    @Autowired private ReservationQuoteRepository quoteRepository;
    @Autowired private ReservationRepository reservationRepository;

    @Test
    void onlyOneOfTwoConcurrentRequestsCanReserveTheLastCapacity() throws Exception {
        LocalDate startDate = LocalDate.of(2031, 1, 1);
        LocalDate endDate = LocalDate.of(2031, 4, 1);
        Facility facility = facilityRepository.saveAndFlush(facility());
        UnitType unitType = unitTypeRepository.saveAndFlush(unitType(facility));
        storageUnitRepository.saveAndFlush(storageUnit(facility, unitType));
        policyRepository.saveAndFlush(policy(facility));

        User firstCustomer = userRepository.saveAndFlush(customer("concurrency-a@storagehub.test"));
        User secondCustomer = userRepository.saveAndFlush(customer("concurrency-b@storagehub.test"));
        ReservationQuote firstQuote = quoteRepository.saveAndFlush(
            quote(firstCustomer, facility, unitType, startDate, endDate)
        );
        ReservationQuote secondQuote = quoteRepository.saveAndFlush(
            quote(secondCustomer, facility, unitType, startDate, endDate)
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Result> first = executor.submit(() -> createAfterBarrier(
                actor(firstCustomer), request(firstQuote), "concurrency-a", ready, start
            ));
            Future<Result> second = executor.submit(() -> createAfterBarrier(
                actor(secondCustomer), request(secondQuote), "concurrency-b", ready, start
            ));
            ready.await();
            start.countDown();

            List<Result> results = List.of(first.get(), second.get());
            assertThat(results).filteredOn(Result::success).hasSize(1);
            assertThat(results).filteredOn(result -> result.status() == 409).hasSize(1);
        }

        boolean firstExists = reservationRepository.findByCustomer_IdAndIdempotencyKey(
            firstCustomer.getId(), "concurrency-a").isPresent();
        boolean secondExists = reservationRepository.findByCustomer_IdAndIdempotencyKey(
            secondCustomer.getId(), "concurrency-b").isPresent();
        assertThat(firstExists ^ secondExists).isTrue();
        assertThat(capacityService.availableCount(
            facility.getId(), unitType.getId(), startDate, endDate
        )).isZero();
    }

    private Result createAfterBarrier(ActorPrincipal actor, CreateReservationRequest request,
                                      String key, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            creationService.create(actor, request, key);
            return new Result(true, 200);
        } catch (ApiException exception) {
            return new Result(false, exception.getStatus().value());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Facility facility() {
        Facility facility = new Facility();
        facility.setCode("CONCURRENCY-F01");
        facility.setName("Concurrency Facility");
        facility.setAddress("Test Address");
        facility.setCity("Test City");
        return facility;
    }

    private UnitType unitType(Facility facility) {
        UnitType unitType = new UnitType();
        unitType.setFacility(facility);
        unitType.setCode("C-S");
        unitType.setName("Concurrency Small");
        unitType.setLengthM(new BigDecimal("8"));
        unitType.setWidthM(new BigDecimal("10"));
        unitType.setHeightM(new BigDecimal("5"));
        unitType.setMonthlyPrice(new BigDecimal("5500000"));
        unitType.setMaxLoadKg(new BigDecimal("1000"));
        unitType.setRackCount(4);
        unitType.setRackLengthM(new BigDecimal("2"));
        unitType.setRackWidthM(new BigDecimal("4"));
        unitType.setRackHeightM(new BigDecimal("4.5"));
        return unitType;
    }

    private StorageUnit storageUnit(Facility facility, UnitType unitType) {
        StorageUnit unit = new StorageUnit();
        unit.setFacility(facility);
        unit.setUnitType(unitType);
        unit.setCode("CONCURRENCY-UNIT-01");
        return unit;
    }

    private User customer(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-used-in-service-test");
        user.setFullName(email);
        return user;
    }

    private RentalPackagePolicy policy(Facility facility) {
        RentalPackagePolicy policy = new RentalPackagePolicy();
        policy.setFacility(facility);
        policy.setCode("THREE_MONTHS");
        policy.setName("Three months");
        policy.setRentalMonths(3);
        policy.setDiscountRate(new BigDecimal("0.0300"));
        policy.setPolicyVersion("concurrency-v1");
        policy.setEffectiveFrom(LocalDate.of(2030, 1, 1));
        return policy;
    }

    private ReservationQuote quote(User customer, Facility facility, UnitType unitType,
                                   LocalDate startDate, LocalDate endDate) {
        ReservationQuote quote = new ReservationQuote();
        quote.setCustomer(customer);
        quote.setFacility(facility);
        quote.setUnitType(unitType);
        quote.setPricingPackageCode("THREE_MONTHS");
        quote.setPolicyVersion("concurrency-v1");
        quote.setStartDate(startDate);
        quote.setEndDate(endDate);
        quote.setRentalMonths(3);
        quote.setMonthlyPrice(new BigDecimal("5500000.00"));
        quote.setSubtotal(new BigDecimal("16500000.00"));
        quote.setDiscountRate(new BigDecimal("0.0300"));
        quote.setDiscountAmount(new BigDecimal("495000.00"));
        quote.setTotalAfterDiscount(new BigDecimal("16005000.00"));
        quote.setReservationDepositAmount(new BigDecimal("6402000.00"));
        quote.setSecurityDepositAmount(new BigDecimal("5500000.00"));
        quote.setRemainingRentalAmount(new BigDecimal("9603000.00"));
        quote.setDueAtCheckIn(new BigDecimal("15103000.00"));
        quote.setTotalInitialObligation(new BigDecimal("21505000.00"));
        quote.setQuotedAt(Instant.now());
        quote.setExpiresAt(Instant.now().plusSeconds(600));
        return quote;
    }

    private ActorPrincipal actor(User customer) {
        return new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );
    }

    private CreateReservationRequest request(ReservationQuote quote) {
        GoodsItemRequest item = new GoodsItemRequest(
            GoodsCategory.FURNITURE, null, "Wood", null, "Desk", null, 1,
            new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("15"),
            new BigDecimal("18"), false
        );
        return new CreateReservationRequest(quote.getId(), "Packed", "Concurrency test", List.of(item));
    }

    private record Result(boolean success, int status) {}
}
