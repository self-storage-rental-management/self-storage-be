package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagehub.api.facility.ManagerUnitTypeRequest;
import com.storagehub.api.facility.RackLayout;
import com.storagehub.api.reservation.CreateReservationRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.SystemPermission;
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
import com.storagehub.service.support.ai.GeminiSupportAiProvider;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.bootstrap-admin.enabled=false")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@MockitoBean(types = GeminiSupportAiProvider.class)
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
    @Autowired private com.storagehub.domain.repo.ViolationRecoveryRepository recoveryRepository;
    @Autowired private com.storagehub.domain.repo.ReservationGoodsItemRepository goodsItems;
    @Autowired private RackLayoutService rackLayouts;
    @Autowired private FacilityLifecycleService facilityLifecycle;
    @Autowired private jakarta.persistence.EntityManager em;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void checkInCreationSortOverridesPriorityBeforePagination() {
        Facility site = facility(); site.setCode("CHECKIN-CREATED"); em.persist(site);
        UnitType type = unitType(site); type.setCode("CREATED-S"); em.persist(type);
        var owner = userRepository.save(customer("created-sort@storagehub.test"));
        var now = Instant.parse("2026-11-22T04:00:00Z");
        var start = LocalDate.of(2026, 11, 23);
        var quote = quoteRepository.save(quote(owner, site, type, start, start.plusMonths(3)));
        var old = queueItem("OLD-SORT", quote, now.plusSeconds(7200), CheckInStatus.scheduled);
        var recent = queueItem("NEW-SORT", quote, now.plusSeconds(3600), CheckInStatus.scheduled);
        em.flush();
        em.createNativeQuery("update reservations set created_at=:at where id=:id")
            .setParameter("at", now.minusSeconds(86400)).setParameter("id", old.getId()).executeUpdate();
        em.createNativeQuery("update reservations set created_at=:at where id=:id")
            .setParameter("at", now).setParameter("id", recent.getId()).executeUpdate();
        em.clear();
        for (String order : List.of("createdAt,asc", "createdAt,desc")) {
            var result = reservationRepository.findCheckInWorkOrdered("ONGOING", now, Instant.EPOCH,
                Instant.EPOCH, Instant.EPOCH, site.getId(), null, false, false, false, null,
                null, null, true, List.of(site.getId()), order, null, org.springframework.data.domain.PageRequest.of(0, 1));
            assertThat(result.getTotalElements()).isEqualTo(2);
            assertThat(result.getContent()).extracting(row -> row.getReservation().getReservationCode())
                .containsExactly(order.endsWith("asc") ? "OLD-SORT" : "NEW-SORT");
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void checkInGroupsSortBeforePaginationAndRetainReleasedUnits() {
        Facility site = facility();
        site.setCode("CHECKIN-QUEUE");
        em.persist(site);
        UnitType type = unitType(site);
        type.setCode("QUEUE-S");
        em.persist(type);
        var owner = userRepository.save(customer("queue@storagehub.test"));
        var now = Instant.parse("2026-11-22T04:00:00Z");
        var start = LocalDate.of(2026, 11, 23);
        var quote = quoteRepository.save(quote(owner, site, type, start, start.plusMonths(3)));
        queueItem("LATER", quote, now.plusSeconds(86400), null);
        queueItem("NEAR", quote, now.plusSeconds(3600), CheckInStatus.scheduled);
        queueItem("LATE-B", quote, now.minusSeconds(3600), CheckInStatus.scheduled);
        queueItem("LATE-A", quote, now.minusSeconds(3601), null);
        var started = queueItem("STARTED", quote, now.plusSeconds(7200), CheckInStatus.identity_verified);
        started.setHandoverArrivedAt(now);
        queueItem("EXPIRED", quote, now.minusSeconds(8 * 86400), CheckInStatus.scheduled);
        queueItem("ABSENT", quote, now, CheckInStatus.no_show);
        queueItem("REJECTED", quote, now, CheckInStatus.rejected);
        queueItem("CANCELLED", quote, now, CheckInStatus.cancelled);
        queueItem("DONE", quote, now, CheckInStatus.completed);
        em.flush();
        em.clear();

        for (var phase : java.util.Map.of("HANDOVER_WAITING", 5L, "HANDOVER_IN_PROGRESS", 1L, "HANDOVER_STOPPED", 3L).entrySet()) {
            String group = phase.getKey().equals("HANDOVER_STOPPED") ? "PROCESSED" : "ONGOING";
            var grouped = reservationRepository.findCheckInWorkOrdered(group, now,
                ReservationCheckInPolicy.firstValidAppointment(now), Instant.EPOCH, Instant.EPOCH,
                site.getId(), null, false, false, false, null, null, null, true, List.of(site.getId()),
                "priority", phase.getKey(), org.springframework.data.domain.PageRequest.of(0, 1));
            assertThat(grouped.getTotalElements()).isEqualTo(phase.getValue());
            assertThat(grouped.getContent()).hasSize(1);
        }

        for (int page = 0; page < 3; page++) {
            var result = reservationRepository.findCheckInWork("ONGOING", now, ReservationCheckInPolicy.firstValidAppointment(now),
                java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                site.getId(), null, false, false, false, null, null, null, true, List.of(site.getId()),
                org.springframework.data.domain.PageRequest.of(page, 2));
            assertThat(result.getTotalElements()).isEqualTo(6);
            assertThat(result.getContent()).extracting(row -> row.getReservation().getReservationCode())
                .containsExactlyElementsOf(List.of("STARTED", "LATE-A", "LATE-B", "NEAR", "LATER", "EXPIRED").subList(page * 2, page * 2 + 2));
        }
        var history = reservationRepository.findCheckInWork("PROCESSED", now, ReservationCheckInPolicy.firstValidAppointment(now),
            java.time.Instant.EPOCH, java.time.Instant.EPOCH,
            site.getId(), null, false, false, false, null, now, now.plusSeconds(1), true, List.of(site.getId()),
            org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(history.getContent()).extracting(row -> row.getReservation().getReservationCode())
            .containsExactlyInAnyOrder("ABSENT", "CANCELLED", "DONE", "REJECTED");
        var filtered = reservationRepository.findCheckInWork("PROCESSED", now, ReservationCheckInPolicy.firstValidAppointment(now),
            java.time.Instant.EPOCH, java.time.Instant.EPOCH,
            site.getId(), "UNIT-ABSENT", true, false, false, CheckInStatus.no_show, now, now.plusSeconds(1), true, List.of(site.getId()),
            org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(filtered.getContent()).singleElement().satisfies(row -> {
            assertThat(row.getReservation().getAssignedUnit()).isNull();
            assertThat(row.getCheckIn().getStatus()).isEqualTo(CheckInStatus.no_show);
            assertThat(row.getAssignment().getStorageUnit().getCode()).isEqualTo("UNIT-ABSENT");
        });
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void recoveryTransfersLeaveOngoingBeforePaginationAndOverrideOldStatusFilters() {
        var site = facility();
        site.setCode("CHECKIN-RECOVERY");
        em.persist(site);
        var type = unitType(site);
        type.setCode("RECOVERY-S");
        em.persist(type);
        var owner = userRepository.save(customer("checkin-recovery@storagehub.test"));
        var now = Instant.parse("2026-11-27T04:00:00Z");
        var start = LocalDate.of(2026, 11, 27);
        var quote = quoteRepository.save(quote(owner, site, type, start, start.plusMonths(3)));
        queueItem("RECOVERY-NORMAL", quote, now, CheckInStatus.setup_completed);
        for (String code : List.of("RECOVERY-ACTIVE", "RECOVERY-RELEASED")) {
            var reservation = queueItem(code, quote, now, CheckInStatus.setup_completed);
            var rental = new com.storagehub.domain.model.Rental();
            rental.setCustomer(owner);
            rental.setFacility(site);
            rental.setReservation(reservation);
            rental.setStorageUnit(reservation.getAssignedUnit());
            rental.setStartDate(start);
            rental.setContractEndDate(start.plusMonths(3));
            rental.setMonthlyPrice(BigDecimal.ONE);
            rental.setInventoryEscalatedAt(now);
            rental.setStatus(com.storagehub.domain.model.RentalStatus.closing);
            em.persist(rental);
            if (code.endsWith("RELEASED")) {
                // Recovery remains in history even after the original unit is released.
                reservation.setAssignedUnit(null);
                reservation.setStatus(ReservationStatus.CANCELLED);
            }
        }
        em.flush();
        em.clear();
        var page = org.springframework.data.domain.PageRequest.of(0, 1);
        var ongoing = reservationRepository.findCheckInWork("ONGOING", now, now,
            Instant.EPOCH, Instant.EPOCH, site.getId(), "RECOVERY-", false, false, false,
            null, null, null, true, List.of(site.getId()), page);
        assertThat(ongoing.getTotalElements()).isEqualTo(1);
        assertThat(ongoing.getContent()).extracting(row -> row.getReservation().getReservationCode())
            .containsExactly("RECOVERY-NORMAL");
        for (int index = 0; index < 2; index++) {
            var history = reservationRepository.findCheckInWork("PROCESSED", now, now,
                Instant.EPOCH, Instant.EPOCH, site.getId(), "RECOVERY-", false, false, false,
                null, null, null, true, List.of(site.getId()), org.springframework.data.domain.PageRequest.of(index, 1));
            assertThat(history.getTotalElements()).isEqualTo(2);
            assertThat(history.getTotalPages()).isEqualTo(2);
            assertThat(history.getContent()).singleElement().satisfies(row ->
                assertThat(row.getReservation().getReservationCode()).isNotEqualTo("RECOVERY-NORMAL"));
        }
        var oldStatus = reservationRepository.findCheckInWork("ALL", now, now,
            Instant.EPOCH, Instant.EPOCH, site.getId(), "RECOVERY-", true, false, false,
            CheckInStatus.setup_completed, null, null, true, List.of(site.getId()), page);
        assertThat(oldStatus.getTotalElements()).isEqualTo(1);
        assertThat(oldStatus.getContent()).extracting(row -> row.getReservation().getReservationCode())
            .containsExactly("RECOVERY-NORMAL");
        var transferred = reservationRepository.findCheckInWork("PROCESSED", now, now,
            Instant.EPOCH, Instant.EPOCH, site.getId(), "RECOVERY-", true, false, true,
            null, null, null, true, List.of(site.getId()), page);
        assertThat(transferred.getTotalElements()).isEqualTo(2);
        var outsideScope = reservationRepository.findCheckInWork("PROCESSED", now, now,
            Instant.EPOCH, Instant.EPOCH, site.getId(), "RECOVERY-", true, false, true,
            null, null, null, true, List.of(UUID.randomUUID()), page);
        assertThat(outsideScope).isEmpty();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void customerRecoveryPaginationCountsOnlyVisibleOwnedRecordsBeforePaging() {
        var site = facility();
        site.setCode("CUSTOMER-RECOVERY-PAGE");
        em.persist(site);
        var type = unitType(site);
        type.setCode("RECOVERY-PAGE-S");
        em.persist(type);
        var owner = userRepository.save(customer("recovery-page@storagehub.test"));
        var other = userRepository.save(customer("recovery-other@storagehub.test"));
        var now = Instant.parse("2026-11-27T04:00:00Z");
        var start = LocalDate.of(2026, 11, 27);
        var quote = quoteRepository.save(quote(owner, site, type, start, start.plusMonths(3)));
        var otherQuote = quoteRepository.save(quote(other, site, type, start, start.plusMonths(3)));
        var ownedIds = new java.util.ArrayList<UUID>();
        for (int index = 0; index < 15; index++) {
            var booking = queueItem("CP-" + index, index == 14 ? otherQuote : quote, now, CheckInStatus.setup_completed);
            var rental = new com.storagehub.domain.model.Rental();
            rental.setCustomer(booking.getCustomer());
            rental.setFacility(site);
            rental.setReservation(booking);
            rental.setStorageUnit(booking.getAssignedUnit());
            rental.setStartDate(start);
            rental.setContractEndDate(start.plusMonths(3));
            rental.setMonthlyPrice(BigDecimal.ONE);
            em.persist(rental);
            var recovery = new com.storagehub.domain.model.ViolationRecovery();
            recovery.setRental(rental);
            recovery.setReason(com.storagehub.domain.model.ViolationRecovery.Reason.INVENTORY_TIMEOUT);
            recovery.setStage(com.storagehub.domain.model.ViolationRecovery.Stage.AWAITING_ASSIGNMENT);
            recovery.setSourceDeadlineAt(now);
            if (index != 13) recovery.setRequiredAt(now);
            recovery.setActionDueAt(now.plusSeconds(index * 60L));
            em.persist(recovery);
            if (index < 13) ownedIds.add(recovery.getId());
        }
        em.flush();
        em.clear();
        var first = recoveryRepository.customerPage(owner.getId(), org.springframework.data.domain.PageRequest.of(0, 8));
        var second = recoveryRepository.customerPage(owner.getId(), org.springframework.data.domain.PageRequest.of(1, 8));
        assertThat(first.getTotalElements()).isEqualTo(13);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(row -> row.getId()).containsExactlyElementsOf(ownedIds.subList(0, 8));
        assertThat(second.getContent()).extracting(row -> row.getId()).containsExactlyElementsOf(ownedIds.subList(8, 13));
        assertThat(second.getTotalElements()).isEqualTo(13);
        assertThat(recoveryRepository.customerPage(other.getId(), org.springframework.data.domain.PageRequest.of(0, 8)).getTotalElements()).isEqualTo(1);
        assertThat(recoveryRepository.customerPage(UUID.randomUUID(), org.springframework.data.domain.PageRequest.of(0, 8))).isEmpty();
    }

    private Reservation queueItem(String code, ReservationQuote quote, Instant appointment, CheckInStatus status) {
        var unit = storageUnit(quote.getFacility(), quote.getUnitType());
        unit.setCode("UNIT-" + code);
        em.persist(unit);
        var r = new Reservation();
        r.setReservationCode(code);
        r.setIdempotencyKey(code);
        r.setSourceQuote(quote);
        r.setCustomer(quote.getCustomer());
        r.setFacility(quote.getFacility());
        r.setUnitType(quote.getUnitType());
        r.setStartDate(quote.getStartDate());
        r.setEndDate(quote.getEndDate());
        r.setAppointmentAt(appointment);
        boolean released = status == CheckInStatus.no_show || status == CheckInStatus.rejected || status == CheckInStatus.cancelled;
        if (!released) r.setAssignedUnit(unit);
        r.setStatus(switch (status) {
            case no_show, cancelled -> ReservationStatus.CANCELLED;
            case rejected -> ReservationStatus.REJECTED;
            case completed -> ReservationStatus.COMPLETED;
            case null, default -> ReservationStatus.READY_FOR_CHECKIN;
        });
        em.persist(r);
        var assignment = new UnitAssignment();
        assignment.setReservation(r);
        assignment.setStorageUnit(unit);
        assignment.setAssignedBy(quote.getCustomer());
        assignment.setAssignedAt(appointment.minusSeconds(60));
        if (released) assignment.setStatus(UnitAssignmentStatus.CANCELLED);
        em.persist(assignment);
        if (status != null) {
            var checkIn = new CheckIn();
            checkIn.setReservation(r);
            checkIn.setPerformedBy(quote.getCustomer());
            checkIn.setStatus(status);
            checkIn.setScheduledAt(appointment);
            em.persist(checkIn);
        }
        return r;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void concurrentReservationsRespectCapacityAndKeepTheirOwnCodes(int capacity) throws Exception {
        LocalDate startDate = ReservationCheckInPolicy.plannedWindow(Instant.now(), false)
            .availableAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        LocalDate endDate = startDate.plusMonths(3);
        Facility site = facility();
        site.setCode("CONCURRENCY-F0" + capacity);
        Facility facility = facilityRepository.saveAndFlush(site);
        UnitType draft = unitType(facility);
        ActorPrincipal manager = manager(facility);
        rackLayouts.confirmNewType(manager, draft, previewLayout(manager, draft));
        UnitType unitType = unitTypeRepository.saveAndFlush(draft);
        for (int i = 1; i <= capacity; i++) {
            StorageUnit unit = storageUnit(facility, unitType);
            unit.setCode("CONCURRENCY-" + capacity + "-" + i);
            storageUnitRepository.saveAndFlush(unit);
        }
        policyRepository.saveAndFlush(policy(facility));

        User firstCustomer = userRepository.saveAndFlush(customer("concurrency-a" + capacity + "@storagehub.test"));
        User secondCustomer = userRepository.saveAndFlush(customer("concurrency-b" + capacity + "@storagehub.test"));
        ReservationQuote firstQuote = quoteRepository.saveAndFlush(
            quote(firstCustomer, facility, unitType, startDate, endDate)
        );
        ReservationQuote secondQuote = quoteRepository.saveAndFlush(
            quote(secondCustomer, facility, unitType, startDate, endDate)
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var requests = Map.of(firstCustomer, request(firstQuote), secondCustomer, request(secondQuote));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Result> first = executor.submit(() -> createAfterBarrier(
                actor(firstCustomer), requests.get(firstCustomer), "concurrency-a", ready, start
            ));
            Future<Result> second = executor.submit(() -> createAfterBarrier(
                actor(secondCustomer), requests.get(secondCustomer), "concurrency-b", ready, start
            ));
            ready.await();
            start.countDown();

            List<Result> results = List.of(first.get(), second.get());
            assertThat(results).as("Concurrent creation results: %s", results).filteredOn(Result::success).hasSize(capacity);
            assertThat(results).filteredOn(result -> result.status() == 409).hasSize(2 - capacity);
        }

        var codes = new java.util.HashSet<String>();
        var goodsCodes = new java.util.HashSet<String>();
        for (var entry : requests.entrySet()) {
            User customer = entry.getKey();
            String key = customer == firstCustomer ? "concurrency-a" : "concurrency-b";
            var saved = reservationRepository.findByCustomer_IdAndIdempotencyKey(customer.getId(), key);
            if (saved.isEmpty()) continue;
            var row = saved.get();
            assertThat(row.getReservationCode()).matches("RSV-([23456789ABCDEFGHJKMNPQRSTVWXYZ]{4}-){2}[23456789ABCDEFGHJKMNPQRSTVWXYZ]{4}");
            assertThat(codes.add(row.getReservationCode())).isTrue();
            assertThat(reservationRepository.findByReservationCode(row.getReservationCode()).orElseThrow().getId())
                .isEqualTo(row.getId());
            var goods = goodsItems.findAllByReservation_IdOrderByCreatedAtAsc(row.getId());
            assertThat(goods).singleElement().satisfies(item -> {
                assertThat(item.getGoodsCode()).matches("GDS-[0-9A-F]{12}");
                assertThat(goodsCodes.add(item.getGoodsCode())).isTrue();
            });
            assertThat(creationService.create(actor(customer), entry.getValue(), key).getReservationCode())
                .isEqualTo(row.getReservationCode());
            assertThat(goodsItems.findById(goods.getFirst().getId()).orElseThrow().getGoodsCode())
                .isEqualTo(goods.getFirst().getGoodsCode());
        }
        assertThat(codes).hasSize(capacity);
        assertThat(goodsCodes).hasSize(capacity);
        assertThat(capacityService.availableCount(
            facility.getId(), unitType.getId(), startDate, endDate
        )).isZero();
    }

    @Test
    void confirmedTypeCreatesTheRequestedUnitsWithOneSharedLayout() {
        Facility draft = facility();
        draft.setCode("LAYOUT-F01");
        Facility facility = facilityRepository.saveAndFlush(draft);
        UnitType type = unitType(facility);
        ActorPrincipal manager = manager(facility);
        type.setAisleWidthM(BigDecimal.ONE);
        var configuration = RackLayoutCalculatorTests.config(
            RackLayoutCalculatorTests.rack("R01", 0, 3, 2, 100, 1200));
        var draftLayout = new RackLayout.Draft(
            type.getLengthM(), type.getWidthM(), type.getHeightM(), null, null, null,
            type.getMaxLoadKg(), null, type.getAisleWidthM(), null, null, null, null, null,
            null, null, null, null, null, null, configuration, null);
        var preview = rackLayouts.previewDraft(manager, facility.getId(), draftLayout);
        var layout = new RackLayout.Save(configuration, preview.previewKey());

        assertThatThrownBy(() -> facilityLifecycle.createUnitType(manager, facility.getId(), typeRequest(type, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("xác nhận");
        assertThat(unitTypeRepository.findByFacility_IdOrderByCodeAsc(facility.getId())).isEmpty();
        assertThat(storageUnitRepository.findByFacility_Id(facility.getId())).isEmpty();

        var created = facilityLifecycle.createUnitType(manager, facility.getId(), typeRequest(type, layout));
        var shared = rackLayouts.readForManager(manager, created.id());
        assertThat(created.totalCount()).isEqualTo(3);
        assertThat(shared.result().palletSlotCount()).isPositive().isEqualTo(preview.result().palletSlotCount());
        String storedLayout = unitTypeRepository.findById(created.id()).orElseThrow().getRackLayoutJson();
        assertThat(storedLayout).isNotBlank();
        assertThat(storageUnitRepository.findByFacility_Id(facility.getId()))
            .hasSize(3)
            .allSatisfy(unit -> {
                assertThat(unit.getUnitType().getId()).isEqualTo(created.id());
                assertThat(unit.getRackLayoutJson()).isEqualTo(storedLayout);
            });
    }

    @Test
    void twoFacilitiesAllocatingInTheSameZonePersistSixDistinctPhysicalCodes() throws Exception {
        var start = new java.util.concurrent.CyclicBarrier(2);
        var allCodes = new java.util.HashSet<String>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = new java.util.ArrayList<Future<com.storagehub.api.facility.UnitTypeResponse>>();
            for (int i = 1; i <= 2; i++) {
                var site = facility();
                site.setCode("PARALLEL-F0" + i);
                var saved = facilityRepository.saveAndFlush(site);
                var manager = manager(saved);
                var type = unitType(saved);
                type.setAisleWidthM(BigDecimal.ONE);
                var config = RackLayoutCalculatorTests.config(RackLayoutCalculatorTests.rack("R01", 0, 3, 2, 100, 1200));
                var draft = new RackLayout.Draft(type.getLengthM(), type.getWidthM(), type.getHeightM(), null, null, null,
                    type.getMaxLoadKg(), null, type.getAisleWidthM(), null, null, null, null, null,
                    null, null, null, null, null, null, config, null);
                var layout = new RackLayout.Save(config, rackLayouts.previewDraft(manager, saved.getId(), draft).previewKey());
                var request = typeRequest(type, layout);
                jobs.add(pool.submit(() -> {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return facilityLifecycle.createUnitType(manager, saved.getId(), request);
                }));
            }
            for (var job : jobs) {
                var created = job.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(created.code()).matches("UT-[0-9A-F]{10}");
                assertThat(unitTypeRepository.findByFacility_IdAndCode(created.facilityId(), created.code())
                    .orElseThrow().getId()).isEqualTo(created.id());
                assertThat(storageUnitRepository.findByFacility_Id(created.facilityId())).hasSize(3).allSatisfy(unit -> {
                    assertThat(unit.getCode()).matches("Z-2[0-9]{2}");
                    assertThat(allCodes.add(unit.getCode())).isTrue();
                    assertThat(storageUnitRepository.findByCode(unit.getCode()).orElseThrow().getId()).isEqualTo(unit.getId());
                    assertThat(unit.getUnitType().getId()).isEqualTo(created.id());
                });
            }
        }
    }

    @Test
    void confirmedTypeUsesCalculatedPalletCapacityWithoutStoringLegacyRatingCatalog() {
        Facility draft = facility();
        draft.setCode("RATING-F01");
        Facility facility = facilityRepository.saveAndFlush(draft);
        UnitType type = unitType(facility);
        ActorPrincipal manager = manager(facility);
        RackLayout.Rack rack = RackLayoutCalculatorTests.v6Rack("R01", 30, 30);
        RackLayout.PalletRating one = new RackLayout.PalletRating(210, 170, new BigDecimal("18"),
            new BigDecimal("40"), new BigDecimal("500"));
        RackLayout.PalletRating two = new RackLayout.PalletRating(210, 80, new BigDecimal("18"),
            new BigDecimal("40"), new BigDecimal("500"));
        RackLayout.Configuration configuration = new RackLayout.Configuration(
            new RackLayout.Clearances(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            List.of(rack), one.toPallet(), true, List.of(one, two));
        RackLayout.Draft draftLayout = new RackLayout.Draft(type.getLengthM(), type.getWidthM(), type.getHeightM(),
            type.getMaxLoadKg(), configuration);
        RackLayout.Preview preview = rackLayouts.previewDraft(manager, facility.getId(), draftLayout);

        rackLayouts.confirmNewType(manager, type, new RackLayout.Save(configuration, preview.previewKey()));
        UnitType saved = unitTypeRepository.saveAndFlush(type);

        assertThat(saved.getPalletRatingCatalogJson()).isNull();
        var result = rackLayouts.readForManager(manager, saved.getId()).result();
        assertThat(result.rulesVersion()).isEqualTo(StorageHubRules.RULES_VERSION);
        assertThat(result.maxFeasiblePalletCount()).isEqualTo(preview.result().maxFeasiblePalletCount());
    }

    private RackLayout.Save previewLayout(ActorPrincipal manager, UnitType type) {
        var config = RackLayoutCalculatorTests.config(RackLayoutCalculatorTests.rack("R01", 0, 3, 2, 100, 1200));
        var draft = new RackLayout.Draft(type.getLengthM(), type.getWidthM(), type.getHeightM(), type.getMaxLoadKg(), config);
        return new RackLayout.Save(config, rackLayouts.previewDraft(manager, type.getFacility().getId(), draft).previewKey());
    }

    private ManagerUnitTypeRequest typeRequest(UnitType type, RackLayout.Save layout) {
        return new ManagerUnitTypeRequest(null, type.getName(), null, type.getLengthM(), type.getWidthM(),
            type.getHeightM(), type.getMaxLoadKg(), 0, type.getRackLengthM(), type.getRackWidthM(), type.getRackHeightM(),
            type.getAisleWidthM(), "Xe đẩy", 0, 0, null, 3, "2", "Khu Z", null, List.of(), List.of(), layout);
    }

    private ActorPrincipal manager(Facility facility) {
        return new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.MANAGE_INVENTORY.code()), Map.of(facility.getId(), FacilityScopeLevel.MANAGE));
    }

    private Result createAfterBarrier(ActorPrincipal actor, CreateReservationRequest request,
                                      String key, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            creationService.create(actor, request, key);
            return new Result(true, 200, null);
        } catch (ApiException exception) {
            return new Result(false, exception.getStatus().value(), exception.getMessage());
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
        unitType.setSecurityDepositAmount(new BigDecimal("5500000"));
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
        policy.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        return policy;
    }

    private ReservationQuote quote(User customer, Facility facility, UnitType unitType,
                                   LocalDate startDate, LocalDate endDate) {
        ReservationQuote quote = com.storagehub.testing.LegacyReservationQuoteFixture.create();
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
        // Reservation fee is 20% of rent after discounts plus the security deposit.
        quote.setReservationDepositAmount(new BigDecimal("4301000.00"));
        quote.setSecurityDepositAmount(new BigDecimal("5500000.00"));
        quote.setRemainingRentalAmount(new BigDecimal("11704000.00"));
        quote.setDueAtCheckIn(new BigDecimal("17204000.00"));
        quote.setTotalInitialObligation(new BigDecimal("21505000.00"));
        quote.setQuotedAt(Instant.now());
        quote.setExpiresAt(Instant.now().plusSeconds(600));
        quote.setSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        quote.setSetupEarlyExitFee(quote.getMonthlyPrice());
        // The race tests capacity for an already-accepted declaration, not live catalog setup.
        quote.setGoodsSnapshotJson(GoodsSnapshotCodec.encode(request(quote).getGoodsItems()));
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
        var request = new CreateReservationRequest(quote.getId(), "Packed", "Concurrency test", List.of(item), Instant.now().plusSeconds(86400));
        request.setAcceptedSetupEarlyExitPolicyVersion(quote.getSetupEarlyExitPolicyVersion());
        request.setAcceptedHandoverTermsVersion(ReservationCheckInPolicy.ATTENDANCE_TERMS);
        return request;
    }

    private record Result(boolean success, int status, String message) {}
}
