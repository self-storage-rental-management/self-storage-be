package com.storagehub.api.configuration;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.CentralizedBusinessPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Centralized Business Policy Governance")
public class CentralizedBusinessPolicyController {

    private final ActorContext actorContext;
    private final CentralizedBusinessPolicyService policyService;

    public CentralizedBusinessPolicyController(
        ActorContext actorContext,
        CentralizedBusinessPolicyService policyService
    ) {
        this.actorContext = actorContext;
        this.policyService = policyService;
    }

    // ==========================================
    // BO Operations
    // ==========================================
    @GetMapping("/api/business/centralized-policy/active")
    @Operation(summary = "Xem chính sách tập trung đang có hiệu lực thi hành")
    public ApiResponse<CentralizedPolicyResponse> getActivePolicy() {
        return new ApiResponse<>(
            policyService.getActivePolicy(),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/api/business/centralized-policy/draft")
    @Operation(summary = "Lấy bản nháp chính sách hiện tại hoặc khởi tạo bản nháp mới từ chính sách đang hiệu lực")
    public ApiResponse<CentralizedPolicyResponse> getDraft() {
        return new ApiResponse<>(
            policyService.getDraftOrCreate(actorContext.required()),
            CorrelationIdContext.current()
        );
    }

    @PutMapping("/api/business/centralized-policy/draft")
    @Operation(summary = "Lưu cập nhật vào bản nháp chính sách (chưa ban hành)")
    public ApiResponse<CentralizedPolicyResponse> saveDraft(
        @Valid @RequestBody UpdateCentralizedPolicyRequest request
    ) {
        return new ApiResponse<>(
            policyService.saveDraft(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/api/business/centralized-policy/preview-changes")
    @Operation(summary = "So sánh đối chiếu chi tiết thay đổi giữa bản nháp và chính sách đang ban hành")
    public ApiResponse<List<PolicyDiffItem>> previewChanges(
        @RequestParam(required = false) UUID draftId
    ) {
        return new ApiResponse<>(
            policyService.previewChanges(actorContext.required(), draftId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/api/business/centralized-policy/publish")
    @Operation(summary = "Thẩm định và chính thức ban hành chính sách tập trung xuống toàn hệ thống cơ sở")
    public ApiResponse<CentralizedPolicyResponse> publishPolicy(
        @RequestParam(required = false) UUID draftId,
        @Valid @RequestBody PublishCentralizedPolicyRequest request
    ) {
        return new ApiResponse<>(
            policyService.publishPolicy(actorContext.required(), draftId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/api/business/centralized-policy/rollback/{versionId}")
    @Operation(summary = "Tạo bản nháp mới khôi phục cấu hình từ phiên bản lịch sử trước đó")
    public ApiResponse<CentralizedPolicyResponse> rollback(
        @PathVariable UUID versionId
    ) {
        return new ApiResponse<>(
            policyService.rollbackToVersion(actorContext.required(), versionId),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/api/business/centralized-policy/history")
    @Operation(summary = "Tra cứu toàn bộ lịch sử ban hành và các phiên bản chính sách")
    public ApiResponse<List<CentralizedPolicyResponse>> listHistory() {
        return new ApiResponse<>(
            policyService.listPublicationHistory(),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/api/business/centralized-policy/history/{id}")
    @Operation(summary = "Xem chi tiết một phiên bản chính sách cụ thể trong lịch sử")
    public ApiResponse<CentralizedPolicyResponse> getHistoryItem(@PathVariable UUID id) {
        return new ApiResponse<>(
            policyService.getPolicyVersion(id),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/api/business/centralized-policy/simulate")
    @Operation(summary = "Mô phỏng tính toán tiền cọc và phí phạt theo chính sách đang ban hành")
    public ApiResponse<PolicyCalculationSimulationResponse> simulate(
        @RequestBody PolicyCalculationSimulationRequest request
    ) {
        return new ApiResponse<>(
            policyService.simulateCalculation(request),
            CorrelationIdContext.current()
        );
    }

    // ==========================================
    // Customer & Facility Integrations (Rule 8 & Section 4)
    // ==========================================
    @GetMapping("/api/customer/centralized-policy/active")
    @Operation(summary = "Khách hàng tra cứu văn bản chính sách & quy định lưu kho chính thức đang hiệu lực")
    public ApiResponse<CentralizedPolicyResponse> getCustomerActivePolicy() {
        return new ApiResponse<>(
            policyService.getActivePolicy(),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/api/facility/centralized-policy/active")
    @Operation(summary = "Quản lý cơ sở (Facility Manager) tra cứu chính sách tập trung đang thi hành (Chỉ đọc)")
    public ApiResponse<CentralizedPolicyResponse> getFacilityActivePolicy() {
        return new ApiResponse<>(
            policyService.getActivePolicy(),
            CorrelationIdContext.current()
        );
    }
}
