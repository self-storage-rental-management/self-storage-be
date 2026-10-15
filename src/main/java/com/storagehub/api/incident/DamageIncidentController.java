package com.storagehub.api.incident;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.domain.model.IncidentSeverity;
import com.storagehub.domain.model.IncidentStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.DamageIncidentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business/incidents")
public class DamageIncidentController {

    private final ActorContext actorContext;
    private final DamageIncidentService damageIncidentService;

    public DamageIncidentController(ActorContext actorContext, DamageIncidentService damageIncidentService) {
        this.actorContext = actorContext;
        this.damageIncidentService = damageIncidentService;
    }

    @GetMapping
    public ApiResponse<List<DamageIncidentResponse>> listIncidents(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) IncidentStatus status,
        @RequestParam(required = false) IncidentSeverity severity
    ) {
        return new ApiResponse<>(
            damageIncidentService.listIncidents(actorContext.required(), facilityId, status, severity),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<DamageIncidentResponse> getIncident(@PathVariable UUID id) {
        return new ApiResponse<>(
            damageIncidentService.getIncident(actorContext.required(), id),
            CorrelationIdContext.current()
        );
    }

    @PostMapping
    public ApiResponse<DamageIncidentResponse> createIncident(
        @Valid @RequestBody DamageIncidentRequest request
    ) {
        return new ApiResponse<>(
            damageIncidentService.createIncident(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @PutMapping("/{id}/status")
    public ApiResponse<DamageIncidentResponse> updateIncidentStatus(
        @PathVariable UUID id,
        @Valid @RequestBody DamageIncidentStatusUpdateRequest request
    ) {
        return new ApiResponse<>(
            damageIncidentService.updateIncidentStatus(actorContext.required(), id, request),
            CorrelationIdContext.current()
        );
    }
}
