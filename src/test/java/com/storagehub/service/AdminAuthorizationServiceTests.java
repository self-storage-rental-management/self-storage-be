package com.storagehub.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.security.ActorPrincipal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminAuthorizationServiceTests {

    private final AdminAuthorizationService service = new AdminAuthorizationService();

    @Test
    void rejectsNonAdminActorsFromSystemAdminDashboard() {
        ActorPrincipal manager = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.VIEW_DASHBOARD.code()),
            Map.of()
        );

        assertThrows(ApiException.class, () -> service.requireAdmin(manager, SystemPermission.VIEW_DASHBOARD));
    }

    @Test
    void allowsActiveAdminActorsWithTheRequiredPermission() {
        ActorPrincipal admin = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.ADMIN),
            Set.of(SystemPermission.VIEW_DASHBOARD.code()),
            Map.of()
        );

        assertDoesNotThrow(() -> service.requireAdmin(admin, SystemPermission.VIEW_DASHBOARD));
    }
}
