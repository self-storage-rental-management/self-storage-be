package com.storagehub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.transaction.PlatformTransactionManager;

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
public class IntakeScenarioDemoSeederTests {

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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    public static String binToUuid(UUID val) {
        return val == null ? "" : val.toString();
    }

    @Test
    void runnerSeedsScenariosIncludingRecoveryWithoutAnExistingTransaction() throws Exception {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS business_record_codes (
                resource_key VARCHAR(120) PRIMARY KEY,
                type_code CHAR(2) NOT NULL,
                period CHAR(4) NOT NULL,
                display_code VARCHAR(16) NOT NULL UNIQUE,
                recorded_at TIMESTAMP(6) NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS money_receipt_codes (
                receipt_code VARCHAR(32) NOT NULL,
                source_type VARCHAR(32) NOT NULL,
                source_id BINARY(16) NOT NULL
            )
            """);
        jdbcTemplate.execute("CREATE ALIAS IF NOT EXISTS BIN_TO_UUID FOR \"com.storagehub.config.IntakeScenarioDemoSeederTests.binToUuid\"");
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

        when(businessRecordCodes.noticeText(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        intakeScenarioDemoSeeder.seedIntakeScenarios(transactionManager).run();

        for (int i = 1; i <= 21; i++) {
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
        var reservations = customerReservationService.list(actor, null, 0, 50, "corr-1");
        assertThat(reservations.data())
            .extracting(r -> r.getReservationCode())
            .contains("RSV-TEST-01", "RSV-TEST-02", "RSV-TEST-09", "RSV-TEST-17", "RSV-TEST-21");

        var rentals = rentalQueryService.list(actor, RentalQuery.parse(new LinkedMultiValueMap<>(), false), false, "corr-2");
        assertThat(rentals.data()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(rentalRepository.findAll()).hasSizeGreaterThanOrEqualTo(9);
    }
}
