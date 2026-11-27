package com.storagehub.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.returns.ReturnInspectionRequest;
import com.storagehub.domain.model.ReturnFeeScheduleVersion;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.repo.ReturnFeeScheduleVersionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
class ReturnFeeScheduleServiceTests {
    @Autowired ReturnFeeScheduleVersionRepository versions;
    @Autowired EntityManager em;
    final ObjectMapper mapper = new ObjectMapper();
    ReturnFeeScheduleService service;
    ReturnFeeScheduleService.Schedule configured;

    @BeforeEach
    void seedConfiguredBaseline() throws Exception {
        service = new ReturnFeeScheduleService(versions, mapper);
        var prices = new LinkedHashMap<>(service.defaults().prices());
        prices.put("minor_wall_scratch", BigDecimal.valueOf(231000));
        var details = new LinkedHashMap<>(service.defaults().details());
        details.put("minor_wall_scratch", new ReturnFeeScheduleService.Detail("Former label", "Former trigger", "Former applies"));
        configured = new ReturnFeeScheduleService.Schedule(12, prices, details, null);
        versions.saveAndFlush(new ReturnFeeScheduleVersion(0, null, mapper.writeValueAsString(configured), null));
        em.clear();
    }

    @Test
    void overdueMultiplierIsVersionedAndLegacyClientsPreserveIt() {
        var initial = service.get();
        assertEquals(0, initial.overdueMultiplier().compareTo(new BigDecimal("1.5")));
        var published = service.save(new ReturnFeeScheduleService.Schedule(initial.revision(), initial.prices(),
            initial.details(), null, true, new BigDecimal("2.25")), UUID.randomUUID());
        assertEquals(0, service.applicableAt(Instant.parse(published.publishedAt())).overdueMultiplier().compareTo(new BigDecimal("1.5")));
        assertEquals(0, service.applicableAt(Instant.parse(published.publishedAt()).plusNanos(1000)).overdueMultiplier().compareTo(new BigDecimal("2.25")));
        var next = service.save(new ReturnFeeScheduleService.Schedule(published.revision(), published.prices(), published.details(), null), UUID.randomUUID());
        assertEquals(0, next.overdueMultiplier().compareTo(new BigDecimal("2.25")));
        assertThrows(RuntimeException.class, () -> service.save(new ReturnFeeScheduleService.Schedule(next.revision(), next.prices(),
            next.details(), null, true, new BigDecimal("10.01")), UUID.randomUUID()));
    }
    @Test
    void existingUninspectedOrdersKeepConfiguredPricesAndDetailsAcrossTwoPublications() {
        var oldOrder = rentalAt(Instant.now().minusSeconds(100));
        var baselinePayload = versions.findById(0L).orElseThrow().getPayload();
        var publisher = UUID.randomUUID();
        var first = service.save(edited(service.get(), 345000, "First label"), publisher);
        var firstTime = Instant.parse(first.publishedAt());
        var orderAfterFirst = rentalAt(firstTime.plusNanos(1000));
        var firstPayload = versions.findById(1L).orElseThrow().getPayload();
        var second = service.save(edited(first, 456000, "Second label"), publisher);
        em.clear();
        // Reload through real persisted repository queries, rather than cached DTOs.
        assertEquals(BigDecimal.valueOf(231000), service.applicableTo(oldOrder).prices().get("minor_wall_scratch"));
        assertEquals(configured.details(), service.applicableTo(oldOrder).details());
        assertEquals(first, service.applicableTo(orderAfterFirst));
        assertEquals(second, service.applicableAt(Instant.parse(second.publishedAt()).plusNanos(1000)));
        assertEquals(baselinePayload, versions.findById(0L).orElseThrow().getPayload());
        assertEquals(firstPayload, versions.findById(1L).orElseThrow().getPayload());
        assertEquals(publisher.toString(), versions.findById(2L).orElseThrow().getPublishedBy());
        assertEquals(3, versions.count());
    }

    @Test
    void addsEditsAndRemovesFeesOnlyForNewReservations() {
        String custom = "custom_minor_" + "a".repeat(32);
        var prices = new LinkedHashMap<>(service.get().prices());
        var details = new LinkedHashMap<>(service.get().details());
        String cleaning = "custom_cleaning_" + "b".repeat(32);
        prices.put(cleaning, BigDecimal.valueOf(50000));
        details.put(cleaning, new ReturnFeeScheduleService.Detail("Vệ sinh kệ", "Kệ bẩn", "Khách thuê"));
        prices.put(custom, BigDecimal.valueOf(125000));
        details.put(custom, new ReturnFeeScheduleService.Detail("Phí mới", "Nghiệm thu", "Khách thuê"));
        var added = service.save(new ReturnFeeScheduleService.Schedule(0, prices, details, null), null);
        var items = List.of(new ReturnInspectionRequest.DamageItem(custom, BigDecimal.valueOf(2)));
        assertEquals(BigDecimal.valueOf(250000), ReturnDamagePricing.calculate(items, added.prices()));
        prices.put(custom, BigDecimal.valueOf(150000));
        var edited = service.save(new ReturnFeeScheduleService.Schedule(1, prices, details, null), null);
        assertEquals(BigDecimal.valueOf(300000), ReturnDamagePricing.calculate(items, edited.prices()));
        service.save(new ReturnFeeScheduleService.Schedule(2, Map.of(), Map.of(), null), null);
        em.clear();
        assertTrue(service.get().prices().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ReturnDamagePricing.calculate(items, service.get().prices()));
        assertEquals(added.prices(), service.applicableAt(Instant.parse(edited.publishedAt())).prices());
    }

    @Test
    void exactPublicationBoundaryAndMissingReservationStayOnPreviousVersion() {
        var first = service.save(edited(service.get(), 345000, "New label"), UUID.randomUUID());
        var boundary = Instant.parse(first.publishedAt());
        assertEquals(0, service.applicableAt(boundary).revision());
        assertEquals(0, service.applicableAt(boundary.minusNanos(1000)).revision());
        assertEquals(1, service.applicableAt(boundary.plusNanos(1000)).revision());
        assertEquals(0, service.applicableTo(new Rental()).revision());
    }

    @Test
    void importsLegacyPriceOnlyBaselineWithoutResettingConfiguredAmounts() throws Exception {
        versions.deleteAllInBatch(); em.clear();
        var oldPrices = new LinkedHashMap<>(configured.prices());
        oldPrices.remove(ReturnDamagePricing.FRAME_DAMAGE);
        versions.saveAndFlush(new ReturnFeeScheduleVersion(0, null,
            mapper.writeValueAsString(Map.of("revision", 9, "prices", oldPrices)), null));
        assertEquals(configured.prices(), service.get().prices());
        assertEquals(service.defaults().details(), service.get().details());
        service.save(edited(service.get(), 400000, "Published"), UUID.randomUUID());
        assertEquals(configured.prices(), service.applicableAt(Instant.EPOCH).prices());
    }

    @Test
    void frameInventoryUsesThePublishedRateForTheReservation() {
        var baseline = service.get();
        var rates = new LinkedHashMap<>(baseline.prices());
        rates.put(ReturnDamagePricing.FRAME_DAMAGE, BigDecimal.valueOf(250000));
        var published = service.save(new ReturnFeeScheduleService.Schedule(
            baseline.revision(), rates, baseline.details(), null), UUID.randomUUID());
        var items = List.of(new ReturnInspectionRequest.DamageItem(ReturnDamagePricing.FRAME_DAMAGE, BigDecimal.valueOf(2)));
        var after = Instant.parse(published.publishedAt()).plusNanos(1000);
        assertEquals(BigDecimal.ZERO, ReturnDamagePricing.calculate(items, service.applicableAt(Instant.EPOCH).prices()));
        assertEquals(BigDecimal.valueOf(500000), ReturnDamagePricing.calculate(items, service.applicableAt(after).prices()));
    }

    @Test
    void rejectsStalePublicationAndIncompleteOrInvalidFieldsWithoutAddingHistory() {
        var original = service.get();
        service.save(edited(original, 345000, "First"), UUID.randomUUID());
        assertThrows(RuntimeException.class, () -> service.save(edited(original, 456000, "Stale"), UUID.randomUUID()));
        var current = service.get();
        assertThrows(RuntimeException.class, () -> service.save(new ReturnFeeScheduleService.Schedule(1, current.prices()), null));
        var details = new LinkedHashMap<>(current.details());
        details.remove("minor");
        assertThrows(RuntimeException.class, () -> service.save(new ReturnFeeScheduleService.Schedule(1, current.prices(), details, null), null));
        details.put("minor", new ReturnFeeScheduleService.Detail(" ", "trigger", "applies"));
        assertThrows(RuntimeException.class, () -> service.save(new ReturnFeeScheduleService.Schedule(1, current.prices(), details, null), null));
        details.put("minor", new ReturnFeeScheduleService.Detail("x".repeat(161), "trigger", "applies"));
        assertThrows(RuntimeException.class, () -> service.save(new ReturnFeeScheduleService.Schedule(1, current.prices(), details, null), null));
        var prices = new LinkedHashMap<>(current.prices());
        prices.put("minor", prices.get("major"));
        assertThrows(RuntimeException.class, () -> service.save(new ReturnFeeScheduleService.Schedule(1, prices, current.details(), null), null));
        prices.put("minor", new BigDecimal("1.5"));
        assertThrows(RuntimeException.class, () -> service.save(new ReturnFeeScheduleService.Schedule(1, prices, current.details(), null), null));
        assertEquals(2, versions.count());
    }

    @Test
    void longDescriptionsPersistAndRemainDescriptiveOnly() {
        var current = service.get();
        var details = new LinkedHashMap<>(current.details());
        details.replaceAll((code, value) -> new ReturnFeeScheduleService.Detail(value.label(), "x".repeat(1000), "free if true; ".repeat(70)));
        var saved = service.save(new ReturnFeeScheduleService.Schedule(0, current.prices(), details, "1900-01-01T00:00:00Z"), UUID.randomUUID());
        em.clear();
        assertEquals(details, service.get().details());
        assertNotEquals("1900-01-01T00:00:00Z", saved.publishedAt());
        var items = List.of(new ReturnInspectionRequest.DamageItem("minor_wall_scratch", BigDecimal.valueOf(2)));
        assertEquals(BigDecimal.valueOf(462000), ReturnDamagePricing.calculate(items, saved.prices()));
        assertTrue(ReturnDamagePricing.describe(items, saved.prices(), saved.details()).contains("Former label"));
        assertThrows(UnsupportedOperationException.class, () -> saved.prices().put("minor", BigDecimal.ZERO));
    }

    private ReturnFeeScheduleService.Schedule edited(ReturnFeeScheduleService.Schedule current, long amount, String label) {
        var prices = new LinkedHashMap<>(current.prices());
        prices.put("minor_wall_scratch", BigDecimal.valueOf(amount));
        var details = new LinkedHashMap<>(current.details());
        details.put("minor_wall_scratch", new ReturnFeeScheduleService.Detail(label, label + " trigger", label + " applies"));
        return new ReturnFeeScheduleService.Schedule(current.revision(), prices, details, null);
    }

    private Rental rentalAt(Instant createdAt) {
        var reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "createdAt", createdAt);
        var rental = new Rental(); rental.setReservation(reservation); return rental;
    }
}
