package com.storagehub.service;

import com.storagehub.api.configuration.FacilityDeadlinePoliciesRequest;
import com.storagehub.api.configuration.UnitTypeDeadlinePolicy;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FacilityDeadlinePoliciesTests {
    @Mock UnitTypeRepository unitTypes;
    @Mock AdminAuthorizationService authorization;
    @Mock AuditLogService audit;
    @InjectMocks FacilityLifecycleService service;
    final UUID facilityId = UUID.randomUUID();
    final ActorPrincipal actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.BUSINESS), Set.of(), Map.of());
    final UnitTypeDeadlinePolicy valid = new UnitTypeDeadlinePolicy(7, 4, 7, 4);

    UnitType type() {
        UnitType type = spy(new UnitType());
        when(type.getId()).thenReturn(UUID.randomUUID());
        return type;
    }

    @Test void missingTypeRejectsWholeSubmissionBeforeSaving() {
        UnitType a = type(), b = type();
        when(unitTypes.findByFacility_IdOrderByCodeAsc(facilityId)).thenReturn(List.of(a, b));
        assertThatThrownBy(() -> service.updateDeadlinePolicies(actor, facilityId,
            new FacilityDeadlinePoliciesRequest(Map.of(a.getId(), valid)))).hasMessageContaining("đầy đủ");
        verify(unitTypes, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test void blankOrInvalidFieldInOneTypeDoesNotSaveAnyType() {
        UnitType a = type(), b = type();
        when(unitTypes.findByFacility_IdOrderByCodeAsc(facilityId)).thenReturn(List.of(a, b));
        assertThatThrownBy(() -> service.updateDeadlinePolicies(actor, facilityId,
            new FacilityDeadlinePoliciesRequest(Map.of(a.getId(), valid,
                b.getId(), new UnitTypeDeadlinePolicy(0, 3, 5, 3))))).hasMessageContaining("nhập đủ");
        verify(unitTypes, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test void savesEveryCurrentTypeTogetherWithIndependentValues() {
        UnitType a = type(), b = type();
        Facility facility = spy(new Facility());
        when(facility.getId()).thenReturn(facilityId);
        for (var type : List.of(a, b)) {
            type.setFacility(facility);
            when(unitTypes.findById(type.getId())).thenReturn(Optional.of(type));
            when(unitTypes.findByIdForUpdate(type.getId())).thenReturn(Optional.of(type));
        }
        when(unitTypes.findByFacility_IdOrderByCodeAsc(facilityId)).thenReturn(List.of(a, b));
        var policies = Map.of(a.getId(), valid, b.getId(), new UnitTypeDeadlinePolicy(10, 5, 10, 5));
        assertThat(service.updateDeadlinePolicies(actor, facilityId, new FacilityDeadlinePoliciesRequest(policies))).isEqualTo(policies);
        verify(unitTypes).save(a);
        verify(unitTypes).save(b);
        assertThat(a.getSetupDeadlineDays()).isEqualTo(7);
        assertThat(b.getMismatchReturnMaxExtensionDays()).isEqualTo(5);
    }
}
