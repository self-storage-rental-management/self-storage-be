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
        Map<java.util.UUID, FacilityScopeLevel> scopes = scopeRepository.findByUserId(user.getId()).stream()
            .collect(Collectors.toMap(scope -> scope.getFacility().getId(), scope -> scope.getScopeLevel()));
        return new ActorResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getPhone(),
            user.getStatus(),
            user.getRoles().stream().map(role -> role.getCode()).collect(Collectors.toUnmodifiableSet()),
            Map.copyOf(scopes),
            user.isMustChangePassword(),
            user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(permission -> permission.getCode())
                .collect(Collectors.toUnmodifiableSet())
        );
    }
}
