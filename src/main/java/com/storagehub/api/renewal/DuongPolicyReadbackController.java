package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.integration.DuongPolicyReadbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @SecurityRequirement(name="bearerAuth")
@Tag(name="Đối chiếu chính sách gia hạn và hỗ trợ")
public class DuongPolicyReadbackController {
    private final ActorContext actors;
    private final DuongPolicyReadbackService service;
    @GetMapping("/api/business/facilities/{id}/{kind:renewal|communication}-policy/stored")
    @Operation(summary="BO đọc chính sách đã lưu, kể cả chưa có hiệu lực/hết hiệu lực", description="revision không bắt buộc; khi có, đọc đúng lần công bố đã lưu để đối chiếu yêu cầu chưa rõ kết quả. Không thay đổi chính sách hiệu lực của luồng vận hành.")
    public ApiResponse<Object> read(@PathVariable UUID id, @PathVariable String kind, @RequestParam(required=false) Long revision) {
        return new ApiResponse<>(service.read(actors.required(), id, kind, revision), CorrelationIdContext.current());
    }
}
