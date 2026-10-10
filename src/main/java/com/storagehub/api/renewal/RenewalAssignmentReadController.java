package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.integration.RenewalAssignmentReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @SecurityRequirement(name="bearerAuth") @Tag(name="Phân công gia hạn")
public class RenewalAssignmentReadController {
    private final ActorContext actors;
    private final RenewalAssignmentReadService service;
    @GetMapping("/api/manager/renewals/{id}/staff-assignment")
    @Operation(summary="Đọc nhân viên phụ trách gia hạn hiện tại", description="Chỉ đọc nguồn phân công D3, quyền/phạm vi DB hiện hành. Không thay đổi phân công Đặt chỗ/Nhận kho.")
    public ApiResponse<RenewalAssignmentReadService.View> read(@PathVariable UUID id) {
        return new ApiResponse<>(service.read(actors.required(), id), CorrelationIdContext.current());
    }
}
