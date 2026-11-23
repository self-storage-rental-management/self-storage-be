package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.configuration.CentralizedPolicyResponse;
import com.storagehub.api.configuration.PolicyCalculationSimulationRequest;
import com.storagehub.api.configuration.PolicyCalculationSimulationResponse;
import com.storagehub.api.configuration.PolicyDiffItem;
import com.storagehub.api.configuration.PublishCentralizedPolicyRequest;
import com.storagehub.api.configuration.UpdateCentralizedPolicyRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CentralizedPolicy;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CentralizedPolicyRepository;
import com.storagehub.domain.repo.SystemSettingRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CentralizedBusinessPolicyServiceTests {

    @Mock CentralizedPolicyRepository policyRepository;
    @Mock UserRepository userRepository;
    @Mock SystemSettingRepository systemSettingRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock AuditLogService auditLogService;

    private CentralizedBusinessPolicyService service;
    private ActorPrincipal businessActor;
    private ActorPrincipal managerActor;
    private User businessUser;

    @BeforeEach
    void setUp() {
        service = new CentralizedBusinessPolicyService(
            policyRepository,
            userRepository,
            systemSettingRepository,
            authorizationService,
            auditLogService
        );

        UUID businessUserId = UUID.randomUUID();
        businessActor = new ActorPrincipal(
            businessUserId,
            UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of(SystemPermission.VIEW_POLICIES.code(), SystemPermission.MANAGE_POLICIES.code()),
            Map.of()
        );

        managerActor = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.VIEW_POLICIES.code()),
            Map.of()
        );

        businessUser = new User();
        ReflectionTestUtils.setField(businessUser, "id", businessUserId);
        businessUser.setFullName("Nguyen Van Business");
    }

    private CentralizedPolicy samplePolicy(String version, PublicationStatus status, boolean active) {
        CentralizedPolicy p = new CentralizedPolicy();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setPolicyVersion(version);
        p.setPublicationStatus(status);
        p.setActive(active);
        p.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        p.setEffectiveTo(null);
        p.setDepositMandatory(true);
        p.setDepositCalculationMethod("MONTHLY_RENT_MULTIPLE");
        p.setDepositMonthlyMultiple(BigDecimal.valueOf(1.00));
        p.setDepositFixedAmount(new BigDecimal("2000000.00"));
        p.setDepositPercentageRate(new BigDecimal("0.2000"));
        p.setMinimumDepositAmount(new BigDecimal("500000.00"));
        p.setGracePeriodDays(3);
        p.setLatePenaltyCalculationMethod("FIXED_DAILY_AMOUNT");
        p.setLateFeeAmount(new BigDecimal("50000.00"));
        p.setLateFeePercentageRate(new BigDecimal("0.0050"));
        p.setRefundProcessingDays(7);
        p.setAllowEarlyRenewal(true);
        p.setAvailableExtensionPeriods("1,3,6,12,24");
        p.setRenewalAdvanceNoticeDays(15);
        p.setAllowEarlyTermination(true);
        p.setEarlyTerminationNoticeDays(14);
        p.setEarlyTerminationFeeCapMonths(1);
        p.setEarlyTerminationFeeCoefficient(BigDecimal.ONE);
        p.setPricingProtectionEnforced(true);
        p.setPricingVersion("PRICE-2026.1");
        return p;
    }

    @Test
    @DisplayName("RULE 1 & 2: Calculates security deposit according to configured method and enforces minimum")
    void testDepositCalculations() {
        CentralizedPolicy p = samplePolicy("POL-2026.1", PublicationStatus.PUBLISHED, true);
        when(policyRepository.findActivePoliciesForDate(any())).thenReturn(List.of(p));

        // 1. Monthly Rent Multiple (1.0x monthly price = 3,000,000)
        p.setDepositCalculationMethod("MONTHLY_RENT_MULTIPLE");
        p.setDepositMonthlyMultiple(BigDecimal.valueOf(1.0));
        BigDecimal deposit1 = service.calculateDeposit(new BigDecimal("3000000.00"), new BigDecimal("18000000.00"));
        assertThat(deposit1).isEqualByComparingTo("3000000.00");

        // 2. Fixed amount (2,000,000)
        p.setDepositCalculationMethod("FIXED_AMOUNT");
        p.setDepositFixedAmount(new BigDecimal("2000000.00"));
        BigDecimal deposit2 = service.calculateDeposit(new BigDecimal("3000000.00"), new BigDecimal("18000000.00"));
        assertThat(deposit2).isEqualByComparingTo("2000000.00");

        // 3. Percentage of total rent (20% of 18,000,000 = 3,600,000)
        p.setDepositCalculationMethod("PERCENTAGE_OF_TOTAL_RENT");
        p.setDepositPercentageRate(new BigDecimal("0.2000"));
        BigDecimal deposit3 = service.calculateDeposit(new BigDecimal("3000000.00"), new BigDecimal("18000000.00"));
        assertThat(deposit3).isEqualByComparingTo("3600000.00");

        // 4. Minimum deposit floor enforcement (if calculated is 200,000, floor is 500,000)
        p.setDepositCalculationMethod("FIXED_AMOUNT");
        p.setDepositFixedAmount(new BigDecimal("200000.00"));
        p.setMinimumDepositAmount(new BigDecimal("500000.00"));
        BigDecimal deposit4 = service.calculateDeposit(new BigDecimal("3000000.00"), new BigDecimal("18000000.00"));
        assertThat(deposit4).isEqualByComparingTo("500000.00");
    }

    @Test
    @DisplayName("RULE 6: Late payment penalty respects grace period and calculates correct fee")
    void testLatePenaltyCalculation() {
        CentralizedPolicy p = samplePolicy("POL-2026.1", PublicationStatus.PUBLISHED, true);
        p.setGracePeriodDays(3);
        p.setLatePenaltyCalculationMethod("FIXED_DAILY_AMOUNT");
        p.setLateFeeAmount(new BigDecimal("50000.00"));
        when(policyRepository.findActivePoliciesForDate(any())).thenReturn(List.of(p));

        BigDecimal monthlyRent = new BigDecimal("3000000.00");

        // Within grace period (2 days <= 3 days) -> 0 fee
        BigDecimal feeWithinGrace = service.calculateLatePenalty(monthlyRent, 2);
        assertThat(feeWithinGrace).isEqualByComparingTo("0.00");

        // Overdue by 5 days (5 - 3 = 2 chargeable days) -> 2 * 50,000 = 100,000
        BigDecimal feeOverdue = service.calculateLatePenalty(monthlyRent, 5);
        assertThat(feeOverdue).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("GOVERNANCE: Facility Manager cannot modify policy (403 Forbidden)")
    void testManagerCannotMutateCentralizedPolicy() {
        UpdateCentralizedPolicyRequest req = UpdateCentralizedPolicyRequest.ofVersion("POL-MODIFIED");

        assertThatThrownBy(() -> service.saveDraft(managerActor, req))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Chỉ Giám Đốc Thương Mại");

        PublishCentralizedPolicyRequest pubReq = new PublishCentralizedPolicyRequest(
            LocalDate.now(), null, "Unauthorized publish attempt"
        );
        assertThatThrownBy(() -> service.publishPolicy(managerActor, UUID.randomUUID(), pubReq))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Chỉ Giám Đốc Thương Mại");
    }

    @Test
    @DisplayName("GOVERNANCE: Publishing archives older version, activates new policy, and generates diff preview")
    void testPublishLifecycleAndDiffPreview() {
        CentralizedPolicy active = samplePolicy("POL-2026.1", PublicationStatus.PUBLISHED, true);
        CentralizedPolicy draft = samplePolicy("POL-2026.2", PublicationStatus.DRAFT, false);
        draft.setDepositFixedAmount(new BigDecimal("2500000.00"));
        draft.setGracePeriodDays(5);

        when(policyRepository.findActivePoliciesForDate(any())).thenReturn(List.of(active));
        when(policyRepository.findActivePolicies()).thenReturn(List.of(active));
        when(policyRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(userRepository.findById(businessActor.userId())).thenReturn(Optional.of(businessUser));
        when(policyRepository.save(any(CentralizedPolicy.class))).thenAnswer(i -> i.getArgument(0));

        // 1. Preview changes (diff)
        List<PolicyDiffItem> diffs = service.previewChanges(businessActor, draft.getId());
        assertThat(diffs).isNotEmpty();
        PolicyDiffItem depositDiff = diffs.stream().filter(d -> "depositFixedAmount".equals(d.fieldKey())).findFirst().orElseThrow();
        assertThat(depositDiff.changed()).isTrue();
        assertThat(depositDiff.oldValue()).contains("2000000.00");
        assertThat(depositDiff.newValue()).contains("2500000.00");

        // 2. Publish policy
        PublishCentralizedPolicyRequest pubReq = new PublishCentralizedPolicyRequest(
            LocalDate.now(), null, "Cập nhật mức cọc và thời gian ân hạn 2026"
        );
        CentralizedPolicyResponse published = service.publishPolicy(businessActor, draft.getId(), pubReq);

        assertThat(published.policyVersion()).isEqualTo("POL-2026.2");
        assertThat(published.publicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(published.active()).isTrue();
        assertThat(published.publishedByName()).isEqualTo("Nguyen Van Business");

        // Old active policy was marked ARCHIVED and active=false
        assertThat(active.isActive()).isFalse();
        assertThat(active.getPublicationStatus()).isEqualTo(PublicationStatus.ARCHIVED);
    }

    @Test
    @DisplayName("SIMULATION: Live calculation simulator generates transparent deposit and late fee breakdown")
    void testSimulationCalculator() {
        CentralizedPolicy active = samplePolicy("POL-2026.1", PublicationStatus.PUBLISHED, true);
        active.setDepositCalculationMethod("MONTHLY_RENT_MULTIPLE");
        active.setDepositMonthlyMultiple(BigDecimal.valueOf(1.0));
        active.setGracePeriodDays(3);
        active.setLatePenaltyCalculationMethod("FIXED_DAILY_AMOUNT");
        active.setLateFeeAmount(new BigDecimal("50000.00"));
        when(policyRepository.findActivePoliciesForDate(any())).thenReturn(List.of(active));

        PolicyCalculationSimulationRequest req = new PolicyCalculationSimulationRequest(
            new BigDecimal("3500000.00"), 6, 7
        );
        PolicyCalculationSimulationResponse res = service.simulateCalculation(req);

        assertThat(res.totalRentValue()).isEqualByComparingTo("21000000.00");
        assertThat(res.calculatedDepositAmount()).isEqualByComparingTo("3500000.00");
        assertThat(res.depositFormulaDescription()).contains("1 tháng tiền thuê");

        // 7 overdue days - 3 grace days = 4 chargeable days * 50,000 = 200,000
        assertThat(res.chargeableOverdueDays()).isEqualTo(4);
        assertThat(res.calculatedPenaltyAmount()).isEqualByComparingTo("200000.00");
        assertThat(res.penaltyFormulaDescription()).contains("4 ngày tính phạt");
    }
}
