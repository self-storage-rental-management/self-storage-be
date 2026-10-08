package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 32)
    private RoleCode code;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Version of the built-in role policy applied to this role. Nullable keeps
     * the column compatible with existing local databases during migration.
     */
    @Column(name = "permissions_policy_version")
    private Integer permissionsPolicyVersion;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "parent_role_id")
    private Role parentRole;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new HashSet<>();

    /**
     * Returns permissions granted directly to this role and inherited from
     * its parent roles. The visited set prevents an invalid database cycle
     * from causing recursive authorization failures.
     */
    public Set<Permission> getEffectivePermissions() {
        return collectEffectivePermissions(new HashSet<>());
    }

    private Set<Permission> collectEffectivePermissions(Set<Role> visited) {
        if (!visited.add(this)) {
            return Set.of();
        }

        Map<String, Permission> effective = new HashMap<>();
        permissions.forEach(permission -> effective.put(permission.getCode(), permission));
        if (parentRole != null) {
            parentRole.collectEffectivePermissions(visited)
                .forEach(permission -> effective.putIfAbsent(permission.getCode(), permission));
        }
        return new HashSet<>(effective.values());
    }
}
