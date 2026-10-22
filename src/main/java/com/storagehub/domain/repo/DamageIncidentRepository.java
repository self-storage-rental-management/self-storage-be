package com.storagehub.domain.repo;

import com.storagehub.domain.model.DamageIncident;
import com.storagehub.domain.model.IncidentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DamageIncidentRepository extends JpaRepository<DamageIncident, UUID> {

    Optional<DamageIncident> findByIncidentCode(String incidentCode);

    List<DamageIncident> findByFacility_IdOrderByReportedAtDesc(UUID facilityId);

    List<DamageIncident> findByFacility_IdAndStatusOrderByReportedAtDesc(UUID facilityId, IncidentStatus status);

    List<DamageIncident> findAllByOrderByReportedAtDesc();
}
