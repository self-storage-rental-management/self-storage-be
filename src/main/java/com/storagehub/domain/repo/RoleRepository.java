package com.storagehub.domain.repo;

import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.RoleCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByCode(RoleCode code);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select role from Role role where role.code = :code")
    Optional<Role> findByCodeForUpdate(@org.springframework.data.repository.query.Param("code") RoleCode code);
}
