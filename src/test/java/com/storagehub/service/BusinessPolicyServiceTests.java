package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.configuration.BusinessConfigResponse;
import com.storagehub.api.configuration.RentalPackagePolicyRequest;
import com.storagehub.api.configuration.RentalPackagePolicyResponse;
import com.storagehub.api.configuration.UpdateBusinessConfigRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.SystemSetting;
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
class BusinessPolicyServiceTests {

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
        facility.setName("StorageHub Tân Bình");

        businessActor = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of("policies:update", "policies:read"),
            Map.of(facilityId, com.storagehub.domain.model.FacilityScopeLevel.MANAGE)
        );
    }

    @Test
    void getBusinessConfig_success() {
        SystemSetting graceSetting = new SystemSetting();
        graceSetting.setValue("5");
        when(systemSettingRepository.findBySettingKey("gracePeriod")).thenReturn(Optional.of(graceSetting));

        SystemSetting lateFeeSetting = new SystemSetting();
        lateFeeSetting.setValue("500000");
        when(systemSettingRepository.findBySettingKey("lateFeeAmount")).thenReturn(Optional.of(lateFeeSetting));

        BusinessConfigResponse config = service.getBusinessConfig(businessActor);

        assertThat(config).isNotNull();
        assertThat(config.gracePeriodDays()).isEqualTo(5);
        assertThat(config.lateFeeAmount()).isEqualByComparingTo("500000");
        verify(authorizationService).require(businessActor, SystemPermission.VIEW_POLICIES);
    }

    @Test
    void updateBusinessConfig_success() {
        UpdateBusinessConfigRequest req = new UpdateBusinessConfigRequest(
            7,
            new BigDecimal("700000"),
            new BigDecimal("0.25"),
            3.0,
            6000,
            true,
            "Bảo trì hệ thống",
            10,
            false
        );

        when(systemSettingRepository.findBySettingKey(any())).thenReturn(Optional.empty());

        BusinessConfigResponse updated = service.updateBusinessConfig(businessActor, req);

        assertThat(updated).isNotNull();
        verify(authorizationService).require(businessActor, SystemPermission.MANAGE_POLICIES);
        verify(systemSettingRepository, org.mockito.Mockito.atLeastOnce()).save(any(SystemSetting.class));
    }

    @Test
    void listPackagePolicies_success() {
        RentalPackagePolicy policy = new RentalPackagePolicy();
        ReflectionTestUtils.setField(policy, "id", UUID.randomUUID());
        policy.setFacility(facility);
        policy.setCode("PKG-6M");
        policy.setName("Gói thuê 6 tháng");
        policy.setRentalMonths(6);
        policy.setDiscountRate(new BigDecimal("0.10"));
        policy.setPolicyVersion("v1.0");
        policy.setActive(true);
        policy.setEffectiveFrom(LocalDate.now());

        when(policyRepository.findByFacility_IdOrderByRentalMonthsAsc(facilityId)).thenReturn(List.of(policy));

        List<RentalPackagePolicyResponse> list = service.listPackagePolicies(businessActor, facilityId, false);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).code()).isEqualTo("PKG-6M");
    }

    @Test
    void createPackagePolicy_success() {
        RentalPackagePolicyRequest req = new RentalPackagePolicyRequest(
            facilityId,
            "PKG-12M",
            "Gói thuê 1 năm giảm 15%",
            12,
            new BigDecimal("0.15"),
            "v1.0",
            true,
            LocalDate.now(),
            null
        );

        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(policyRepository.findByFacility_IdAndCode(facilityId, "PKG-12M")).thenReturn(Optional.empty());
        when(policyRepository.save(any(RentalPackagePolicy.class))).thenAnswer(inv -> {
            RentalPackagePolicy p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        RentalPackagePolicyResponse resp = service.createPackagePolicy(businessActor, req);

        assertThat(resp).isNotNull();
        assertThat(resp.code()).isEqualTo("PKG-12M");
        assertThat(resp.discountRate()).isEqualByComparingTo("0.15");
        verify(facilityScopeService).assertCanManage(businessActor, facilityId);
    }

    @Test
    void createPackagePolicy_duplicateCode_throwsConflict() {
        RentalPackagePolicyRequest req = new RentalPackagePolicyRequest(
            facilityId,
            "PKG-12M",
            "Gói thuê 1 năm",
            12,
            new BigDecimal("0.15"),
            "v1.0",
            true,
            LocalDate.now(),
            null
        );

        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(policyRepository.findByFacility_IdAndCode(facilityId, "PKG-12M")).thenReturn(Optional.of(new RentalPackagePolicy()));

        assertThatThrownBy(() -> service.createPackagePolicy(businessActor, req))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void updatePackagePolicy_success() {
        UUID policyId = UUID.randomUUID();
        RentalPackagePolicy policy = new RentalPackagePolicy();
        ReflectionTestUtils.setField(policy, "id", policyId);
        policy.setFacility(facility);
        policy.setCode("PKG-3M");
        policy.setName("Gói 3 tháng");
        policy.setRentalMonths(3);
        policy.setDiscountRate(new BigDecimal("0.05"));
        policy.setPolicyVersion("v1.0");
        policy.setActive(true);
        policy.setEffectiveFrom(LocalDate.now());

        RentalPackagePolicyRequest req = new RentalPackagePolicyRequest(
            facilityId,
            "PKG-3M",
            "Gói 3 tháng ưu đãi mới",
            3,
            new BigDecimal("0.08"),
            "v1.1",
            true,
            LocalDate.now(),
            null
        );

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyRepository.save(any(RentalPackagePolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        RentalPackagePolicyResponse resp = service.updatePackagePolicy(businessActor, policyId, req);

        assertThat(resp).isNotNull();
        assertThat(resp.name()).isEqualTo("Gói 3 tháng ưu đãi mới");
        assertThat(resp.discountRate()).isEqualByComparingTo("0.08");
    }

    @Test
    void deletePackagePolicy_deactivatesPolicy() {
        UUID policyId = UUID.randomUUID();
        RentalPackagePolicy policy = new RentalPackagePolicy();
        ReflectionTestUtils.setField(policy, "id", policyId);
        policy.setFacility(facility);
        policy.setCode("PKG-3M");
        policy.setActive(true);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        service.deletePackagePolicy(businessActor, policyId);

        assertThat(policy.isActive()).isFalse();
        verify(policyRepository).save(policy);
    }
}
