package com.storagehub.api.credential;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.AccessCredentialStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.AccessCredentialService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccessCredentialController {

    private final ActorContext actorContext;
    private final AccessCredentialService service;

    @PostMapping("/staff/check-ins/{checkInId}/credentials")
    public ApiResponse<AccessCredentialResponse> issue(
        @PathVariable UUID checkInId,
        @Valid @RequestBody IssueAccessCredentialRequest request
    ) {
        return new ApiResponse<>(
            service.issue(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/staff/access-credentials/{credentialId}/revoke")
    public ApiResponse<AccessCredentialResponse> revoke(
        @PathVariable UUID credentialId,
        @Valid @RequestBody RevokeAccessCredentialRequest request
    ) {
        return new ApiResponse<>(
            service.revoke(actorContext.required(), credentialId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/staff/access-credentials/{credentialId}/access-events")
    public ApiResponse<CredentialAccessEventResponse> recordAccessEvent(
        @PathVariable UUID credentialId,
        @Valid @RequestBody RecordCredentialAccessEventRequest request
    ) {
        return new ApiResponse<>(
            service.recordAccessEvent(actorContext.required(), credentialId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/staff/rentals/{rentalId}/credentials/reissue")
    public ApiResponse<AccessCredentialResponse> reissue(
        @PathVariable UUID rentalId,
        @Valid @RequestBody ReissueAccessCredentialRequest request
    ) {
        return new ApiResponse<>(
            service.reissue(actorContext.required(), rentalId, request),
            CorrelationIdContext.current()
        );
    }

    /** Staff and Manager can monitor history; only Staff may mutate credentials. */
    @GetMapping({"/staff/access-credentials", "/manager/access-credentials"})
    public PageResponse<AccessCredentialResponse> list(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) AccessCredentialStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return service.list(
            actorContext.required(), facilityId, q, status, page, pageSize,
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/staff/credential-alerts")
    public java.util.List<CredentialAlertResponse> alerts(
        @RequestParam(required = false) UUID facilityId
    ) {
        return service.alerts(actorContext.required(), facilityId);
    }
}
