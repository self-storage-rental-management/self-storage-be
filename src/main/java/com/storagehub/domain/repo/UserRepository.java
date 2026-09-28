package com.storagehub.domain.repo;

import com.storagehub.domain.model.User;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByRoles_Code(RoleCode code);

    @Query("""
        select distinct u from User u
        left join u.roles assignedRole
        where (:search is null
            or lower(u.fullName) like lower(concat('%', :search, '%'))
            or lower(u.email) like lower(concat('%', :search, '%'))
            or lower(coalesce(u.phone, '')) like lower(concat('%', :search, '%')))
          and (:role is null or assignedRole.code = :role)
          and (:status is null or u.status = :status)
          and (:facilityId is null or exists (
              select scope from UserFacilityScope scope
              where scope.user.id = u.id and scope.facility.id = :facilityId
          ))
        """)
    Page<User> search(
        @Param("search") String search,
        @Param("role") RoleCode role,
        @Param("status") UserStatus status,
        @Param("facilityId") UUID facilityId,
        Pageable pageable
    );
}
