package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Opt-in read-only diagnostic against the configured local database. */
@EnabledIfSystemProperty(named="storagehub.local-read-smoke", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK, properties={
    "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none", "app.bootstrap-demo-data.enabled=false"
})
class MaintenanceLocalReadSmokeTests {
    @Autowired MaintenanceService service;
    @Test void readsEmptyFacilityMaintenanceTasks() {
        UUID facility = UUID.fromString("37c8af59-b28f-4a6d-8b99-9df8a3e5172c");
        var actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.VIEW_UNITS.code(), SystemPermission.MANAGE_STAFF_TASKS.code()),
            Map.of(facility, FacilityScopeLevel.MANAGE));
        service.listTasks(actor, facility, null, null, null, null, null, 0, 100, "local-read-diagnostic");
    }
}
