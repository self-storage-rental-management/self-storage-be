package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.renewal.RenewalReadService;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** No grants, sessions, schema or real DB data are changed by these assertions. */
class RenewalGuardActivationAndScopeTests {
    private final UUID facility=UUID.randomUUID();
    @Test void guardIsAbsentByDefaultAndDoesNotInstallRuntimeSources() {
        new ApplicationContextRunner().withBean(EntityManager.class,()->mock(EntityManager.class))
            .withUserConfiguration(RenewalOperationalGuard.class).run(context->{
                assertThat(context).doesNotHaveBean(RenewalOperationalGuard.class);
                assertThat(context).doesNotHaveBean(com.storagehub.service.renewal.RenewalSources.EligibilitySource.class);
                assertThat(context).doesNotHaveBean(com.storagehub.service.renewal.RenewalSources.ExtensionHoldSource.class);
                assertThat(context).doesNotHaveBean(com.storagehub.service.renewal.RenewalSources.ApprovalLifecycleSource.class);
            });
    }
    @Test void optInOnlyRegistersReadOnlyGuardNotApprovalCapability() {
        new ApplicationContextRunner().withBean(EntityManager.class,()->mock(EntityManager.class))
            .withUserConfiguration(RenewalOperationalGuard.class)
            .withPropertyValues("storagehub.integration.renewal-operational-checks.enabled=true").run(context->{
                assertThat(context).hasSingleBean(RenewalOperationalGuard.class);
                assertThat(context).doesNotHaveBean(com.storagehub.service.renewal.RenewalSources.EligibilitySource.class);
                assertThat(context).doesNotHaveBean(com.storagehub.service.renewal.RenewalSources.ExtensionHoldSource.class);
            });
    }
    @Test void managerReadGrantCannotAuthorizeDecision() {
        var actor=actor(RoleCode.MANAGER,"rentals:read",FacilityScopeLevel.MANAGE);
        assertThatThrownBy(()->RenewalReadService.authorize(actor,true,true)).hasMessageContaining("permission");
    }
    @Test void updateGrantWithoutManageScopeCannotAuthorizeDecision() {
        var actor=actor(RoleCode.MANAGER,"rentals:update",FacilityScopeLevel.READ);
        assertThatThrownBy(()->RenewalReadService.authorize(actor,true,true)).hasMessageContaining("scope");
    }
    @Test void properlyGrantedManagerCannotOperateAnotherFacility() {
        var actor=actor(RoleCode.MANAGER,"rentals:update",FacilityScopeLevel.MANAGE);
        assertThatCode(()->RenewalReadService.authorize(actor,true,true)).doesNotThrowAnyException();
        assertThatThrownBy(()->RenewalReadService.requireScope(actor,UUID.randomUUID(),FacilityScopeLevel.MANAGE))
            .hasMessageContaining("scope");
    }
    @Test void staffDoesNotGainManagerApprovalFromPermissionAlone() {
        var actor=actor(RoleCode.STAFF,"rentals:update",FacilityScopeLevel.MANAGE);
        assertThatThrownBy(()->RenewalReadService.authorize(actor,true,true)).hasMessageContaining("Manager");
    }
    private ActorPrincipal actor(RoleCode role,String permission,FacilityScopeLevel level) {
        return new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(role),Set.of(permission),Map.of(facility,level));
    }
}
