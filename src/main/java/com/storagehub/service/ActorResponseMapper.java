package com.storagehub.service;

import com.storagehub.api.auth.ActorResponse;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ActorResponseMapper {

    private final UserFacilityScopeRepository scopeRepository;

    public ActorResponse toResponse(User user) {
        var assignedScopes = scopeRepository.findByUserId(user.getId());
        Map<java.util.UUID, FacilityScopeLevel> scopes = assignedScopes.stream()
            .collect(Collectors.toMap(scope -> scope.getFacility().getId(), scope -> scope.getScopeLevel()));
        Map<java.util.UUID, String> facilityNames = assignedScopes.stream()
            .collect(Collectors.toMap(scope -> scope.getFacility().getId(), scope -> scope.getFacility().getName()));
        return new ActorResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getPhone(),
            user.getPermanentAddress(),
            user.getEmergencyContactName(),
            user.getEmergencyContactPhone(),
            user.getAvatarUrl(),
            user.getStatus(),
            user.getRoles().stream().map(role -> role.getCode()).collect(Collectors.toUnmodifiableSet()),
            Map.copyOf(scopes),
            Map.copyOf(facilityNames),
            user.isMustChangePassword(),
            user.getRoles().stream()
                .flatMap(role -> role.getEffectivePermissions().stream())
                .map(permission -> permission.getCode())
                .collect(Collectors.toUnmodifiableSet())
        );
    }
}
