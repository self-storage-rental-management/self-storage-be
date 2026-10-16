package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.incident.DamageIncidentRequest;
import com.storagehub.api.incident.DamageIncidentResponse;
import com.storagehub.api.incident.DamageIncidentStatusUpdateRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.DamageCause;
import com.storagehub.domain.model.DamageIncident;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.IncidentSeverity;
import com.storagehub.domain.model.IncidentStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.DamageIncidentRepository;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DamageIncidentServiceTests {

    @Mock DamageIncidentRepository incidentRepository;
    @Mock FacilityRepository facilityRepository;
    @Mock StorageUnitRepository storageUnitRepository;
    @Mock UserRepository userRepository;
    @Mock FacilityScopeService facilityScopeService;
    @Mock AuditLogService auditLogService;

    private DamageIncidentService service;
    private ActorPrincipal staffActor;
    private Facility facility;
    private UUID facilityId;
    private StorageUnit unit;
    private UUID unitId;
    private User staffUser;

    @BeforeEach
    void setUp() {
        service = new DamageIncidentService(
            incidentRepository,
            facilityRepository,
            storageUnitRepository,
            userRepository,
            facilityScopeService,
            auditLogService
        );

        facilityId = UUID.randomUUID();
        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        facility.setName("Facility North");

        unitId = UUID.randomUUID();
        unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "id", unitId);
        unit.setCode("U-101");
        unit.setFacility(facility);
        unit.setStatus(StorageUnitStatus.occupied);

        UUID staffId = UUID.randomUUID();
        staffUser = new User();
        ReflectionTestUtils.setField(staffUser, "id", staffId);
        staffUser.setFullName("Nguyen Van Staff");

        staffActor = new ActorPrincipal(
            staffId, UUID.randomUUID(),
            Set.of(RoleCode.STAFF),
            Set.of(),
            Map.of(facilityId, FacilityScopeLevel.OPERATE)
        );
    }

    @Test
    void createsIncidentWithStorageUnitAndPreservesUnitStatus() {
        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(storageUnitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        when(userRepository.findById(staffActor.userId())).thenReturn(Optional.of(staffUser));
        when(incidentRepository.save(any(DamageIncident.class))).thenAnswer(i -> {
            DamageIncident d = i.getArgument(0);
            ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            return d;
        });

        DamageIncidentRequest req = new DamageIncidentRequest(
            facilityId, unitId, DamageCause.HUMAN, IncidentSeverity.MEDIUM,
            "Broken door hinge caused during moving goods",
            Instant.now(), null
        );

        DamageIncidentResponse res = service.createIncident(staffActor, req);
        assertThat(res.incidentCode()).startsWith("INC-");
        assertThat(res.storageUnitCode()).isEqualTo("U-101");
        assertThat(res.status()).isEqualTo(IncidentStatus.REPORTED);
        // Unit status must remain occupied (no automatic change)
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.occupied);
    }

    @Test
    void createsFacilityWideIncidentWithoutStorageUnit() {
        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(userRepository.findById(staffActor.userId())).thenReturn(Optional.of(staffUser));
        when(incidentRepository.save(any(DamageIncident.class))).thenAnswer(i -> {
            DamageIncident d = i.getArgument(0);
            ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            return d;
        });

        // Common infrastructure incident: storageUnitId is null
        DamageIncidentRequest req = new DamageIncidentRequest(
            facilityId, null, DamageCause.NATURE, IncidentSeverity.HIGH,
            "Corridor water leakage near entrance after heavy rain",
            Instant.now(), null
        );

        DamageIncidentResponse res = service.createIncident(staffActor, req);
        assertThat(res.storageUnitId()).isNull();
        assertThat(res.storageUnitCode()).isNull();
        assertThat(res.severity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(res.status()).isEqualTo(IncidentStatus.REPORTED);
    }

    @Test
    void rejectsStatusUpdateToResolvedWithoutResolutionNotes() {
        UUID incidentId = UUID.randomUUID();
        DamageIncident incident = new DamageIncident();
        ReflectionTestUtils.setField(incident, "id", incidentId);
        incident.setFacility(facility);
        incident.setStatus(IncidentStatus.INVESTIGATING);

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        DamageIncidentStatusUpdateRequest updateReq = new DamageIncidentStatusUpdateRequest(
            IncidentStatus.RESOLVED, "" // blank resolution notes
        );

        assertThatThrownBy(() -> service.updateIncidentStatus(staffActor, incidentId, updateReq))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Ghi chú xử lý (resolutionNotes) là bắt buộc");
    }

    @Test
    void allowsStatusUpdateToResolvedWithResolutionNotes() {
        UUID incidentId = UUID.randomUUID();
        DamageIncident incident = new DamageIncident();
        ReflectionTestUtils.setField(incident, "id", incidentId);
        incident.setFacility(facility);
        incident.setStatus(IncidentStatus.INVESTIGATING);

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(DamageIncident.class))).thenAnswer(i -> i.getArgument(0));

        DamageIncidentStatusUpdateRequest updateReq = new DamageIncidentStatusUpdateRequest(
            IncidentStatus.RESOLVED, "Hinge replaced by contractor and tested"
        );

        DamageIncidentResponse res = service.updateIncidentStatus(staffActor, incidentId, updateReq);
        assertThat(res.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(res.resolutionNotes()).isEqualTo("Hinge replaced by contractor and tested");
    }
}
