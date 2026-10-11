package com.storagehub.api.support;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.communication.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Tag(name="Thông báo và chính sách xem xét hỗ trợ")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
@ConditionalOnProperty(name="storagehub.integration.communication.enabled",havingValue="true")
public class CommunicationIntegrationController {
    private final ActorContext actors;private final CommunicationPolicyService policies;private final NotificationDeliveryService delivery;
    @GetMapping("/api/business/facilities/{id}/communication-policy")
    public ApiResponse<CommunicationPolicyService.Policy> policy(@PathVariable UUID id){return new ApiResponse<>(policies.get(actors.required(),id),CorrelationIdContext.current());}
    @PutMapping("/api/business/facilities/{id}/communication-policy") @Operation(summary="Công bố chính sách nhắc quá hạn và thời gian xem xét hỗ trợ")
    public ApiResponse<CommunicationPolicyService.Policy> publish(@PathVariable UUID id,@RequestBody CommunicationPolicyService.Input input){return new ApiResponse<>(policies.publish(actors.required(),id,input),CorrelationIdContext.current());}
    @GetMapping("/api/customer/notifications/{id}/acknowledgement") @Operation(summary="Đọc trạng thái và khả năng xác nhận thông báo",description="Chỉ đọc tracking hiện hành của Customer; UNTRACKED không có nút xác nhận. Không đổi isRead, receipt hoặc trạng thái Support.")
    public ApiResponse<NotificationDeliveryService.AcknowledgementState> acknowledgementState(@PathVariable UUID id){return new ApiResponse<>(delivery.acknowledgementState(actors.required(),id),CorrelationIdContext.current());}
    @PostMapping("/api/customer/notifications/{id}/acknowledgement") @Operation(summary="Khách hàng xác nhận đã nhận thông báo trong ứng dụng, không phải xác nhận đã giải quyết hỗ trợ")
    public ApiResponse<NotificationDeliveryService.Acknowledgement> acknowledge(@PathVariable UUID id){return new ApiResponse<>(delivery.acknowledge(actors.required(),id),CorrelationIdContext.current());}
}
