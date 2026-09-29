package com.storagehub.config;

import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.Permission;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.PermissionRepository;
import com.storagehub.domain.repo.RoleRepository;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RoleDataInitializer {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    @Bean
    CommandLineRunner seedSystemRoles() {
        return args -> {
            Map<SystemPermission, Permission> permissions = new EnumMap<>(SystemPermission.class);
            Arrays.stream(SystemPermission.values()).forEach(code -> {
                Permission permission = permissionRepository.findByCode(code.code()).orElseGet(() -> {
                    Permission created = new Permission();
                    created.setCode(code.code());
                    created.setName(code.code());
                    return permissionRepository.save(created);
                });
                permissions.put(code, permission);
            });

            Arrays.stream(RoleCode.values()).forEach(code ->
                roleRepository.findByCode(code).orElseGet(() -> {
                    Role role = new Role();
                    role.setCode(code);
                    role.setName(code.name());
                    return roleRepository.save(role);
                })
            );

            Role admin = roleRepository.findByCode(RoleCode.ADMIN).orElseThrow();
            admin.getPermissions().addAll(Arrays.asList(
                permissions.get(SystemPermission.VIEW_DASHBOARD),
                permissions.get(SystemPermission.MANAGE_USERS),
                permissions.get(SystemPermission.MANAGE_ROLES),
                permissions.get(SystemPermission.VIEW_AUDIT_LOGS),
                permissions.get(SystemPermission.MANAGE_SETTINGS)
            ));
            roleRepository.save(admin);
        };
    }
}
