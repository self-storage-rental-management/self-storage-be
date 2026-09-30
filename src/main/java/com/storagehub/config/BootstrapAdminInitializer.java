package com.storagehub.config;

import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.RoleRepository;
import com.storagehub.domain.repo.UserRepository;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@ConditionalOnProperty(prefix = "app.bootstrap-admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class BootstrapAdminInitializer {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap-admin.email:}")
    private String email;

    @Value("${app.bootstrap-admin.password:}")
    private String password;

    @Value("${app.bootstrap-admin.full-name:StorageHub Administrator}")
    private String fullName;

    @Bean
    @Order(100)
    CommandLineRunner createBootstrapAdmin() {
        return args -> {
            String normalizedEmail = normalizeEmail(email);
            String configuredPassword = password == null ? "" : password;

            validateConfiguration(normalizedEmail, configuredPassword);

            if (userRepository.countByRoles_Code(RoleCode.ADMIN) > 0) {
                return;
            }

            if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
                throw new IllegalStateException(
                    "Bootstrap admin email already belongs to a non-admin account"
                );
            }

            Role adminRole = roleRepository.findByCode(RoleCode.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role has not been initialized"));

            User admin = new User();
            admin.setEmail(normalizedEmail);
            admin.setPasswordHash(passwordEncoder.encode(configuredPassword));
            admin.setFullName(normalizeFullName(fullName));
            admin.setStatus(UserStatus.ACTIVE);
            admin.setMustChangePassword(true);
            admin.setRoles(new HashSet<>(Set.of(adminRole)));
            userRepository.saveAndFlush(admin);
        };
    }

    private void validateConfiguration(String normalizedEmail, String configuredPassword) {
        if (normalizedEmail.isBlank()) {
            throw new IllegalStateException("STORAGEHUB_BOOTSTRAP_ADMIN_EMAIL is required when bootstrap is enabled");
        }
        if (configuredPassword.length() < 12 || configuredPassword.length() > 128) {
            throw new IllegalStateException(
                "STORAGEHUB_BOOTSTRAP_ADMIN_PASSWORD must contain between 12 and 128 characters"
            );
        }
        if (normalizeFullName(fullName).isBlank()) {
            throw new IllegalStateException("STORAGEHUB_BOOTSTRAP_ADMIN_FULL_NAME must not be blank");
        }
    }

    private String normalizeEmail(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeFullName(String rawFullName) {
        return rawFullName == null ? "" : rawFullName.trim();
    }
}
