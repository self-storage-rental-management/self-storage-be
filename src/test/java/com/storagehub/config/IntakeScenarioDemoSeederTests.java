package com.storagehub.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.api.rental.RentalQuery;
import com.storagehub.service.CustomerReservationService;
import com.storagehub.service.RentalQueryService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "app.bootstrap-demo-data.enabled=true",
        "app.seed-intake-scenarios.enabled=true",
        "app.bootstrap-admin.enabled=false",
        "storagehub.integration.rental-period.enabled=false"
    }
)
@ActiveProfiles("test")
class IntakeScenarioDemoSeederTests {

    @MockitoBean private com.storagehub.service.support.ai.GeminiSupportAiProvider geminiSupportAiProvider;
    @MockitoBean private com.storagehub.service.BusinessRecordCodes businessRecordCodes;
    @Autowired private FacilityRepository facilityRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LocalDemoDataInitializer localDemoDataInitializer;
    @Autowired private IntakeScenarioDemoSeeder intakeScenarioDemoSeeder;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RentalRepository rentalRepository;
    @Autowired private CustomerReservationService customerReservationService;
    @Autowired private RentalQueryService rentalQueryService;

    @Test
    void seedsAllNineScenariosVisibleInBothCustomerReservationsAndRentals() throws Exception {
        if (facilityRepository.findByCodeIgnoreCase("HCM-Q1-F01").isEmpty()) {
            Facility facility = new Facility();
            facility.setCode("HCM-Q1-F01");
            facility.setName("Kho Quận 1 Demo");
            facility.setAddress("123 Lê Lợi, Quận 1");
            facility.setCity("TP.HCM");
            facility.setPhone("02838221111");
            facility.setAccessHours("06:00-22:00");
            facility.setStatus(FacilityStatus.active);
            facilityRepository.saveAndFlush(facility);
        }
        localDemoDataInitializer.seedLocalDemoData().run();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User thao = userRepository.findByEmailIgnoreCase("thanhthao725218@gmail.com").orElseGet(() -> {
            User u = new User();
            u.setEmail("thanhthao725218@gmail.com");
            u.setFullName("Thanh Thảo");
            u.setPhone("0909123456");
            u.setPasswordHash("encoded");
            u.setStatus(UserStatus.ACTIVE);
            u.setRoles(new HashSet<>(Set.of(customerRole)));
            return userRepository.saveAndFlush(u);
        });

        intakeScenarioDemoSeeder.seedAll();

        for (int i = 1; i <= 9; i++) {
            String code = String.format("RSV-TEST-%02d", i);
            assertThat(reservationRepository.existsByReservationCode(code))
                .as("Reservation %s should exist", code)
                .isTrue();
        }

        ActorPrincipal actor = new ActorPrincipal(
            thao.getId(),
            UUID.randomUUID(),
            Set.of(RoleCode.CUSTOMER),
            Set.of(),
            Map.of()
        );
        var reservations = customerReservationService.list(actor, null, 0, 20, "corr-1");
        assertThat(reservations.data())
            .extracting(r -> r.getReservationCode())
            .contains("RSV-TEST-01", "RSV-TEST-02", "RSV-TEST-09");

        var rentals = rentalQueryService.list(actor, RentalQuery.parse(new LinkedMultiValueMap<>(), false), false, "corr-2");
        assertThat(rentals.data()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(rentalRepository.findAll()).hasSizeGreaterThanOrEqualTo(9);
    }
}
