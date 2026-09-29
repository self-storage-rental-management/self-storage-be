package com.storagehub.api.admin;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final ActorContext actorContext;
    private final AdminUserService adminUserService;

    @GetMapping
    public PageResponse<AdminUserResponse> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) RoleCode role,
        @RequestParam(required = false) UserStatus status,
        @RequestParam(required = false) UUID facilityId
    ) {
        return adminUserService.list(
            actorContext.required(), page, size, search, role, status, facilityId, CorrelationIdContext.current()
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserResponse> get(@PathVariable UUID id) {
        return new ApiResponse<>(adminUserService.get(actorContext.required(), id), CorrelationIdContext.current());
    }

    @PostMapping
    public ApiResponse<AdminUserResponse> create(@Valid @RequestBody AdminCreateUserRequest request) {
        return new ApiResponse<>(adminUserService.create(actorContext.required(), request), CorrelationIdContext.current());
    }

    @PatchMapping("/{id}")
    public ApiResponse<AdminUserResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody AdminPatchUserRequest request
    ) {
        return new ApiResponse<>(adminUserService.update(actorContext.required(), id, request), CorrelationIdContext.current());
    }

    @PatchMapping("/{id}/roles")
    public ApiResponse<AdminUserResponse> updateRoles(
        @PathVariable UUID id,
        @Valid @RequestBody AdminUpdateRolesRequest request
    ) {
        return new ApiResponse<>(adminUserService.updateRoles(actorContext.required(), id, request), CorrelationIdContext.current());
    }

    @PatchMapping("/{id}/facilities")
    public ApiResponse<AdminUserResponse> updateFacilities(
        @PathVariable UUID id,
        @Valid @RequestBody AdminUpdateFacilitiesRequest request
    ) {
        return new ApiResponse<>(adminUserService.updateFacilities(actorContext.required(), id, request), CorrelationIdContext.current());
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AdminUserResponse> updateStatus(
        @PathVariable UUID id,
        @Valid @RequestBody AdminStatusUpdateRequest request
    ) {
        return new ApiResponse<>(adminUserService.updateStatus(actorContext.required(), id, request), CorrelationIdContext.current());
    }

    @PostMapping("/{id}/unlock")
    public ApiResponse<AdminUserResponse> unlock(@PathVariable UUID id) {
        return new ApiResponse<>(adminUserService.unlock(actorContext.required(), id), CorrelationIdContext.current());
    }

    @PostMapping("/{id}/password-reset")
    public ApiResponse<AdminUserResponse> resetPassword(
        @PathVariable UUID id,
        @Valid @RequestBody AdminPasswordResetRequest request
    ) {
        return new ApiResponse<>(adminUserService.resetPassword(actorContext.required(), id, request), CorrelationIdContext.current());
    }
}
