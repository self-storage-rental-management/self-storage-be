package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.configuration.RentalPackagePolicyRequest;
import com.storagehub.api.configuration.RentalPackagePolicyResponse;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.SystemSettingRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
class BusinessPolicyLifecycleTests {

    @Mock RentalPackagePolicyRepository policyRepository;
    @Mock FacilityRepository facilityRepository;
    @Mock SystemSettingRepository systemSettingRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock FacilityScopeService facilityScopeService;
    @Mock AuditLogService auditLogService;

    private BusinessPolicyService service;
    private ActorPrincipal businessActor;
    private Facility facility;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        service = new BusinessPolicyService(
            policyRepository,
            facilityRepository,
            systemSettingRepository,
            authorizationService,
            facilityScopeService,
            auditLogService
        );

        facilityId = UUID.randomUUID();
        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        facility.setName("Facility Central");

        businessActor = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of(SystemPermission.VIEW_POLICIES.code(), SystemPermission.MANAGE_POLICIES.code()),
            Map.of()
        );
    }

    @Test
    void rejectsDirectEditWhenPolicyIsPublished() {
        UUID policyId = UUID.randomUUID();
        RentalPackagePolicy policy = new RentalPackagePolicy();
        ReflectionTestUtils.setField(policy, "id", policyId);
        policy.setFacility(facility);
        policy.setCode("PKG-12M");
        policy.setName("Annual Package");
        policy.setRentalMonths(12);
        policy.setPublicationStatus(PublicationStatus.PUBLISHED);
        policy.setActive(true);
        policy.setEffectiveFrom(LocalDate.of(2026, 1, 1));

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        RentalPackagePolicyRequest updateRequest = new RentalPackagePolicyRequest(
            facilityId, "PKG-12M", "Updated Name", 12, 1, "Desc",
            new BigDecimal("0.10"), "v2", PublicationStatus.PUBLISHED, true,
            LocalDate.of(2026, 1, 1), null
        );

        assertThatThrownBy(() -> service.updatePackagePolicy(businessActor, policyId, updateRequest))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Cấu hình đã xuất bản không được chỉnh sửa trực tiếp");
    }

    @Test
    void allowsEditAfterInactivatingPolicyThenPublishAgain() {
        UUID policyId = UUID.randomUUID();
        RentalPackagePolicy policy = new RentalPackagePolicy();
        ReflectionTestUtils.setField(policy, "id", policyId);
        policy.setFacility(facility);
        policy.setCode("PKG-12M");
        policy.setName("Annual Package");
        policy.setRentalMonths(12);
        policy.setPublicationStatus(PublicationStatus.PUBLISHED);
        policy.setActive(true);
        policy.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        policy.setPolicyVersion("v1");

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyRepository.save(any(RentalPackagePolicy.class))).thenAnswer(i -> i.getArgument(0));

        // 1. Inactivate
        RentalPackagePolicyResponse inactivated = service.inactivePackagePolicy(businessActor, policyId);
        assertThat(inactivated.publicationStatus()).isEqualTo(PublicationStatus.INACTIVE);
        assertThat(inactivated.runtimeStatus()).isEqualTo("INACTIVE");
        assertThat(inactivated.active()).isFalse();

        // 2. Edit while INACTIVE
        RentalPackagePolicyRequest updateRequest = new RentalPackagePolicyRequest(
            facilityId, "PKG-12M", "Updated 1-Year Policy", 12, 1, "Updated Desc",
            new BigDecimal("0.15"), "v2", PublicationStatus.INACTIVE, false,
            LocalDate.of(2026, 1, 1), null
        );
        RentalPackagePolicyResponse updated = service.updatePackagePolicy(businessActor, policyId, updateRequest);
        assertThat(updated.name()).isEqualTo("Updated 1-Year Policy");
        assertThat(updated.rentalMonths()).isEqualTo(12);

        // 3. Publish again
        when(policyRepository.findByFacility_IdAndRentalMonths(facilityId, 12)).thenReturn(List.of(policy));
        RentalPackagePolicyResponse published = service.publishPackagePolicy(businessActor, policyId);
        assertThat(published.publicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(published.runtimeStatus()).isEqualTo("ACTIVE");
        assertThat(published.active()).isTrue();
    }

    @Test
    void createsDraftPolicyWithRentalYearsConvertedToRentalMonths() {
        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(policyRepository.findByFacility_IdAndCode(facilityId, "PKG-2Y")).thenReturn(Optional.empty());
        when(policyRepository.save(any(RentalPackagePolicy.class))).thenAnswer(i -> {
            RentalPackagePolicy p = i.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        // 2 years provided with null rentalMonths
        RentalPackagePolicyRequest req = new RentalPackagePolicyRequest(
            facilityId, "PKG-2Y", "2 Year Plan", null, 2, "Long term rental",
            new BigDecimal("0.20"), "v1", PublicationStatus.DRAFT, false,
            LocalDate.of(2026, 1, 1), null
        );

        RentalPackagePolicyResponse created = service.createPackagePolicy(businessActor, req);
        assertThat(created.rentalMonths()).isEqualTo(24);
        assertThat(created.rentalYears()).isEqualTo(2);
        assertThat(created.publicationStatus()).isEqualTo(PublicationStatus.DRAFT);
        assertThat(created.runtimeStatus()).isEqualTo("DRAFT");
        assertThat(created.active()).isFalse();
    }
}
