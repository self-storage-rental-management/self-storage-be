package com.storagehub.domain.repo;

import com.storagehub.domain.model.Permission;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    Optional<Permission> findByCode(String code);
}
