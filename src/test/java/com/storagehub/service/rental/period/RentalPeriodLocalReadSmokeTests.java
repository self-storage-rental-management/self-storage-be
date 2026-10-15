package com.storagehub.service.rental.period;

import com.storagehub.domain.model.Rental;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "storagehub.local-period-smoke", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none",
    "app.bootstrap-demo-data.enabled=false", "app.bootstrap-admin.enabled=false",
    "storagehub.integration.rental-period.enabled=true"
})
@Transactional(readOnly = true)
class RentalPeriodLocalReadSmokeTests {
    @Autowired EntityManager em;
    @Autowired RentalPeriodResolver resolver;
    @Test void newestProvenHandoverHasVerifiedPeriod() {
        var ids = em.createNativeQuery("SELECT BIN_TO_UUID(r.id) FROM rentals r JOIN activity_logs a ON a.entity_id=r.reservation_id AND a.action='CUSTOMER_RECEIPT_CONFIRMED' WHERE JSON_UNQUOTE(JSON_EXTRACT(a.after_state_json,'$.rentalPeriodEvidence.schema'))='CUSTOMER_RECEIPT_PERIOD_V1' ORDER BY r.created_at DESC LIMIT 1").getResultList();
        assertThat(ids).isNotEmpty();
        var rental = em.find(Rental.class, UUID.fromString(ids.getFirst().toString()));
        assertThat(resolver.read(rental)).isPresent();
    }
}
