package com.storagehub.service;

import com.storagehub.api.incident.DamageIncidentRequest;
import com.storagehub.api.incident.DamageIncidentResponse;
import com.storagehub.api.incident.DamageIncidentStatusUpdateRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.DamageIncident;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.IncidentSeverity;
import com.storagehub.domain.model.IncidentStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.DamageIncidentRepository;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DamageIncidentService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final DamageIncidentRepository incidentRepository;
    private final FacilityRepository facilityRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UserRepository userRepository;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<DamageIncidentResponse> listIncidents(
        ActorPrincipal actor,
        UUID facilityId,
        IncidentStatus status,
        IncidentSeverity severity
    ) {
        requireOperationsOrBusiness(actor);

        List<DamageIncident> list;
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
            list = status != null
                ? incidentRepository.findByFacility_IdAndStatusOrderByReportedAtDesc(facilityId, status)
                : incidentRepository.findByFacility_IdOrderByReportedAtDesc(facilityId);
        } else {
            list = incidentRepository.findAllByOrderByReportedAtDesc();
            if (!facilityScopeService.hasGlobalScope(actor)) {
                list = list.stream()
                    .filter(i -> facilityScopeService.canAccessFacility(actor, i.getFacility().getId(), FacilityScopeLevel.READ))
                    .toList();
            }
            if (status != null) {
                list = list.stream().filter(i -> i.getStatus() == status).toList();
            }
        }

        if (severity != null) {
            list = list.stream().filter(i -> i.getSeverity() == severity).toList();
        }

        return list.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DamageIncidentResponse getIncident(ActorPrincipal actor, UUID id) {
        requireOperationsOrBusiness(actor);

        DamageIncident incident = incidentRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Sự cố hư hỏng không tồn tại"));

        facilityScopeService.assertCanRead(actor, incident.getFacility().getId());
        return toResponse(incident);
    }

    @Transactional
    public DamageIncidentResponse createIncident(ActorPrincipal actor, DamageIncidentRequest request) {
        requireOperationsOrBusiness(actor);
        facilityScopeService.assertCanOperate(actor, request.facilityId());

        Facility facility = facilityRepository.findById(request.facilityId())
            .orElseThrow(() -> ApiExceptions.notFound("Cơ sở không tồn tại"));

        StorageUnit unit = null;
        if (request.storageUnitId() != null) {
            unit = storageUnitRepository.findById(request.storageUnitId())
                .orElseThrow(() -> ApiExceptions.notFound("Kho lưu trữ không tồn tại"));
            if (!unit.getFacility().getId().equals(facility.getId())) {
                throw ApiExceptions.validation("Kho lưu trữ không thuộc cơ sở đã chọn", null);
            }
        }

        User reportedBy = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("Tài khoản người dùng không tồn tại"));

        DamageIncident incident = new DamageIncident();
        incident.setIncidentCode(generateUniqueIncidentCode());
        incident.setFacility(facility);
        incident.setStorageUnit(unit);
        incident.setCause(request.cause());
        incident.setSeverity(request.severity());
        incident.setDescription(request.description().trim());
        incident.setOccurredAt(request.occurredAt());
        incident.setReportedAt(Instant.now());
        incident.setReportedBy(reportedBy);
        incident.setStatus(IncidentStatus.REPORTED);
        incident.setEvidencePhotosJson(request.evidencePhotosJson());

        DamageIncident saved = incidentRepository.save(incident);

        auditLogService.recordMutation(
            "DAMAGE_INCIDENT_REPORTED",
            "DamageIncident",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional
    public DamageIncidentResponse updateIncidentStatus(
        ActorPrincipal actor,
        UUID id,
        DamageIncidentStatusUpdateRequest request
    ) {
        requireOperationsOrBusiness(actor);

        DamageIncident incident = incidentRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Sự cố hư hỏng không tồn tại"));

        facilityScopeService.assertCanOperate(actor, incident.getFacility().getId());

        if ((request.status() == IncidentStatus.RESOLVED || request.status() == IncidentStatus.CLOSED)
            && (request.resolutionNotes() == null || request.resolutionNotes().isBlank())) {
            throw ApiExceptions.validation("Ghi chú xử lý (resolutionNotes) là bắt buộc khi chuyển trạng thái sang RESOLVED hoặc CLOSED", null);
        }

        DamageIncidentResponse before = toResponse(incident);

        incident.setStatus(request.status());
        if (request.resolutionNotes() != null && !request.resolutionNotes().isBlank()) {
            incident.setResolutionNotes(request.resolutionNotes().trim());
        }

        DamageIncident saved = incidentRepository.save(incident);
        DamageIncidentResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "DAMAGE_INCIDENT_STATUS_UPDATED",
            "DamageIncident",
            saved.getId(),
            saved.getFacility().getId(),
            before,
            after
        );

        return after;
    }

    private void requireOperationsOrBusiness(ActorPrincipal actor) {
        if (actor == null) throw ApiExceptions.unauthorized("Authentication is required");
        if (!actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.MANAGER, RoleCode.STAFF, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Chỉ nhân viên vận hành, quản lý hoặc BO mới có quyền truy cập sự cố");
        }
    }

    private String generateUniqueIncidentCode() {
        String ym = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        for (int i = 0; i < 10; i++) {
            int randomVal = RANDOM.nextInt(90000) + 10000;
            String candidate = "INC-" + ym + "-" + randomVal;
            if (incidentRepository.findByIncidentCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "INC-" + ym + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public DamageIncidentResponse toResponse(DamageIncident i) {
        return new DamageIncidentResponse(
            i.getId(),
            i.getIncidentCode(),
            i.getFacility().getId(),
            i.getFacility().getName(),
            i.getStorageUnit() != null ? i.getStorageUnit().getId() : null,
            i.getStorageUnit() != null ? i.getStorageUnit().getCode() : null,
            i.getCause(),
            i.getSeverity(),
            i.getDescription(),
            i.getOccurredAt(),
            i.getReportedAt(),
            i.getReportedBy() != null ? i.getReportedBy().getId() : null,
            i.getReportedBy() != null ? i.getReportedBy().getFullName() : null,
            i.getStatus(),
            i.getResolutionNotes(),
            i.getEvidencePhotosJson(),
            i.getCreatedAt(),
            i.getUpdatedAt()
        );
    }
}
