package com.storagehub.api.admin;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.service.AdminRoleService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class AdminRoleController {

    private final ActorContext actorContext;
    private final AdminRoleService adminRoleService;

    @GetMapping
    public ApiResponse<List<AdminRoleResponse>> list() {
        return new ApiResponse<>(adminRoleService.list(actorContext.required()), CorrelationIdContext.current());
    }

    @PatchMapping("/{role}/permissions")
    public ApiResponse<AdminRoleResponse> updatePermissions(
        @PathVariable RoleCode role,
        @Valid @RequestBody AdminUpdateRolePermissionsRequest request
    ) {
        return new ApiResponse<>(
            adminRoleService.updatePermissions(actorContext.required(), role, request),
            CorrelationIdContext.current()
        );
    }
}
