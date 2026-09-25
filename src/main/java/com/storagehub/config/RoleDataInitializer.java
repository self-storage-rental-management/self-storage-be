package com.storagehub.config;

import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.repo.RoleRepository;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RoleDataInitializer {

    private final RoleRepository roleRepository;

    @Bean
    CommandLineRunner seedSystemRoles() {
        return args -> Arrays.stream(RoleCode.values()).forEach(code ->
            roleRepository.findByCode(code).orElseGet(() -> {
                Role role = new Role();
                role.setCode(code);
                role.setName(code.name());
                return roleRepository.save(role);
            })
        );
    }
}
