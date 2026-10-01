package com.storagehub;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "app.bootstrap-admin.enabled=true",
    "app.bootstrap-admin.email=bootstrap-admin@storagehub.test",
    "app.bootstrap-admin.password=bootstrap-password-123",
    "app.bootstrap-admin.full-name=Bootstrap Administrator"
})
@ActiveProfiles("test")
class BootstrapAdminInitializerTests {

    @Autowired
    private UserRepository userRepository;

    @Test
    void createsAnActiveAdminWhenNoAdministratorExists() {
        var admin = userRepository.findByEmailIgnoreCase("bootstrap-admin@storagehub.test");

        assertThat(admin).isPresent();
        assertThat(admin.orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.orElseThrow().getRoles())
            .extracting(role -> role.getCode())
            .containsExactly(RoleCode.ADMIN);
        assertThat(admin.orElseThrow().isMustChangePassword()).isTrue();
    }
}
