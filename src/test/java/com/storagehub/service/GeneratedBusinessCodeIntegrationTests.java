package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.api.facility.CreateFacilityRequest;
import com.storagehub.api.incident.DamageIncidentRequest;
import com.storagehub.api.reservation.GoodsCatalogCategoryUpdateRequest;
import com.storagehub.api.reservation.GoodsCatalogMaterialRequest;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.support.ai.GeminiSupportAiProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"app.bootstrap-admin.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:business-codes;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@DirtiesContext
@MockitoBean(types = GeminiSupportAiProvider.class)
class GeneratedBusinessCodeIntegrationTests {
    @Autowired GoodsCatalogService catalog;
    @Autowired GoodsTypeCatalogRepository categories;
    @Autowired FacilityManagementService management;
    @Autowired FacilityRepository facilities;
    @Autowired RentalPackagePolicyRepository packages;
    @Autowired DamageIncidentService incidents;
    @Autowired DamageIncidentRepository incidentRows;
    @Autowired PaymentRepository payments;
    @Autowired UserRepository users;

    @Test void newCategoriesAndMaterialsKeepTheirOwnCodesWhenReopened() throws Exception {
        var manager = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.MANAGE_INVENTORY.code()), Map.of());
        var created = concurrently(i -> catalog.addCategory(manager,
            new GoodsCatalogCategoryUpdateRequest("Loại hàng " + i, GoodsCatalogStatus.ACTIVE)));
        var materials = concurrently(i -> catalog.addMaterial(manager, created.get(i).id(),
            new GoodsCatalogMaterialRequest("Chất liệu", false, GoodsCatalogStatus.ACTIVE)));
        assertThat(created.getFirst().code()).isNotEqualTo(created.getLast().code());
        assertThat(materials.getFirst().code()).isNotEqualTo(materials.getLast().code());
        var current = catalog.managerCatalog(manager).categories();
        for (int i = 0; i < created.size(); i++) {
            var type = created.get(i);
            var material = materials.get(i);
            assertThat(type.code()).matches("CAT-[0-9A-F]{12}");
            assertThat(material.code()).matches("MAT-[0-9A-F]{12}");
            assertThat(categories.findByCode(type.code()).orElseThrow().getId()).isEqualTo(type.id());
            assertThat(current).filteredOn(category -> category.id().equals(type.id())).singleElement()
                .satisfies(category -> assertThat(category.materials()).containsExactly(material));
        }
    }

    @Test void packagesAreScopedToTheirFacilityAndIncidentCodesIdentifySingleIncidents() throws Exception {
        var operator = user("incident");
        var actor = new ActorPrincipal(operator.getId(), UUID.randomUUID(), Set.of(RoleCode.BUSINESS), Set.of(), Map.of());
        var seed = new Facility();
        seed.setCode("SEED-F01");
        seed.setName("Cơ sở mẫu");
        seed.setAddress("Địa chỉ");
        seed.setCity("Hà Nội");
        var policy = new RentalPackagePolicy();
        policy.setFacility(facilities.saveAndFlush(seed));
        policy.setCode("PKG-3M");
        policy.setName("Gói ba tháng");
        policy.setRentalMonths(3);
        policy.setPolicyVersion("v1");
        policy.setPublicationStatus(PublicationStatus.PUBLISHED);
        policy.setEffectiveFrom(LocalDate.now().minusDays(1));
        packages.saveAndFlush(policy);
        var sites = concurrently(i -> management.createFacility(actor, new CreateFacilityRequest(
            "HN-F01", "Cơ sở " + i, "Địa chỉ", "Hà Nội", null, null, null, false, null, null, null, true)));
        assertThat(sites).extracting(site -> site.code()).containsExactlyInAnyOrder("HN-F01", "HN-F02");
        var manual = new CreateFacilityRequest("HN-F01", "Cơ sở thủ công", "Địa chỉ", "Hà Nội",
            null, null, null, false, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> management.createFacility(actor, manual))
            .isInstanceOf(com.storagehub.common.api.ApiException.class).hasMessageContaining("code already exists");
        var reports = concurrently(i -> incidents.createIncident(actor, new DamageIncidentRequest(sites.get(i).id(),
            null, DamageCause.NATURE, IncidentSeverity.HIGH, "Thấm nước", Instant.now(), null)));
        assertThat(reports.getFirst().incidentCode()).isNotEqualTo(reports.getLast().incidentCode());
        for (int i = 0; i < sites.size(); i++) {
            var site = sites.get(i);
            assertThat(facilities.findByCodeIgnoreCase(site.code()).orElseThrow().getId()).isEqualTo(site.id());
            assertThat(packages.findByFacility_IdAndCode(site.id(), "PKG-1M").orElseThrow().getFacility().getId())
                .isEqualTo(site.id());
            var incident = reports.get(i);
            assertThat(incident.incidentCode()).matches("INC-[0-9]{6}-(?:[0-9]{5}|[0-9A-F]{8})");
            assertThat(incidentRows.findByIncidentCode(incident.incidentCode()).orElseThrow().getId()).isEqualTo(incident.id());
            assertThat(incidents.getIncident(actor, incident.id()).incidentCode()).isEqualTo(incident.incidentCode());
        }
    }

    @Test void concurrentPaymentsKeepTheirInvoiceNumbersAfterRefundAndReload() throws Exception {
        var customer = user("invoice");
        var paid = concurrently(i -> {
            var payment = new Payment();
            payment.setInitiatedBy(customer);
            payment.setPurpose(PaymentType.RENTAL_BALANCE);
            payment.setAmount(BigDecimal.TEN);
            payment.setCurrency("VND");
            payment.setIdempotencyKey("receipt-" + i);
            payment.setGatewayIntentId("transaction-" + i);
            payment.setStatus(PaymentStatus.PENDING);
            var pending = payments.saveAndFlush(payment);
            assertThat(pending.getInvoiceNumber()).isNull();
            pending.setStatus(PaymentStatus.PAID);
            return payments.saveAndFlush(pending);
        });
        assertThat(paid.getFirst().getInvoiceNumber()).isNotEqualTo(paid.getLast().getInvoiceNumber());
        for (var payment : paid) {
            String invoice = payment.getInvoiceNumber();
            assertThat(invoice).matches("INV-[0-9A-F]{32}");
            payment.setRefundedAmount(BigDecimal.ONE);
            payments.saveAndFlush(payment);
            var reopened = payments.findByGatewayIntentId(payment.getGatewayIntentId()).orElseThrow();
            assertThat(reopened.getId()).isEqualTo(payment.getId());
            assertThat(reopened.getInvoiceNumber()).isEqualTo(invoice);
            assertThat(reopened.getInvoiceStatus()).isEqualTo(InvoiceStatus.REFUNDED);
        }
    }

    private User user(String prefix) {
        var user = new User();
        user.setEmail(prefix + "@business-codes.test");
        user.setPasswordHash("unused");
        user.setFullName(prefix);
        return users.saveAndFlush(user);
    }

    private static <T> List<T> concurrently(IntFunction<T> create) throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return create.apply(0); });
            var second = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return create.apply(1); });
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
}
