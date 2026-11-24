package com.storagehub.service;

import com.storagehub.api.configuration.CentralizedPolicyResponse;
import com.storagehub.api.configuration.PolicyCalculationSimulationRequest;
import com.storagehub.api.configuration.PolicyCalculationSimulationResponse;
import com.storagehub.api.configuration.PolicyDiffItem;
import com.storagehub.api.configuration.PublishCentralizedPolicyRequest;
import com.storagehub.api.configuration.UpdateCentralizedPolicyRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.CentralizedPolicy;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.SystemSetting;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CentralizedPolicyRepository;
import com.storagehub.domain.repo.SystemSettingRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CentralizedBusinessPolicyService {

    private final CentralizedPolicyRepository policyRepository;
    private final UserRepository userRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final AdminAuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    public CentralizedBusinessPolicyService(
        CentralizedPolicyRepository policyRepository,
        UserRepository userRepository,
        SystemSettingRepository systemSettingRepository,
        AdminAuthorizationService authorizationService,
        AuditLogService auditLogService
    ) {
        this.policyRepository = policyRepository;
        this.userRepository = userRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public CentralizedPolicy getActiveEntity() {
        LocalDate today = LocalDate.now();
        List<CentralizedPolicy> active = policyRepository.findActivePoliciesForDate(today);
        if (!active.isEmpty()) {
            return active.get(0);
        }
        // Fallback: check any active published policy
        List<CentralizedPolicy> allActive = policyRepository.findActivePolicies();
        if (!allActive.isEmpty()) {
            return allActive.get(0);
        }
        // Seed default policy if empty
        return seedDefaultPolicy();
    }

    @Transactional(readOnly = true)
    public CentralizedPolicyResponse getActivePolicy() {
        CentralizedPolicy policy = getActiveEntity();
        return toResponse(policy);
    }

    @Transactional
    public CentralizedPolicyResponse getDraftOrCreate(ActorPrincipal actor) {
        requirePolicyOwner(actor);
        Optional<CentralizedPolicy> draftOpt = policyRepository.findFirstByPublicationStatusOrderByCreatedAtDesc(PublicationStatus.DRAFT);
        if (draftOpt.isPresent()) {
            return toResponse(draftOpt.get());
        }

        // Create new draft from current active policy
        CentralizedPolicy active = getActiveEntity();
        CentralizedPolicy draft = new CentralizedPolicy();
        draft.setPolicyVersion(generateNextVersionString());
        draft.setPublicationStatus(PublicationStatus.DRAFT);
        draft.setActive(false);
        draft.setEffectiveFrom(LocalDate.now());
        draft.setEffectiveTo(null);
        draft.setChangeSummary("Dự thảo điều chỉnh chính sách vận hành StorageHub.");

        copyPolicyValues(active, draft);

        CentralizedPolicy saved = policyRepository.save(draft);
        return toResponse(saved);
    }

    @Transactional
    public CentralizedPolicyResponse saveDraft(ActorPrincipal actor, UpdateCentralizedPolicyRequest request) {
        requirePolicyOwner(actor);
        CentralizedPolicy draft = policyRepository.findFirstByPublicationStatusOrderByCreatedAtDesc(PublicationStatus.DRAFT)
            .orElseGet(() -> {
                CentralizedPolicy d = new CentralizedPolicy();
                d.setPolicyVersion(generateNextVersionString());
                d.setPublicationStatus(PublicationStatus.DRAFT);
                d.setActive(false);
                d.setEffectiveFrom(LocalDate.now());
                return d;
            });

        if (request.policyVersion() != null && !request.policyVersion().isBlank()) {
            draft.setPolicyVersion(request.policyVersion().trim());
        }
        if (request.effectiveFrom() != null) {
            draft.setEffectiveFrom(request.effectiveFrom());
        }
        if (request.effectiveTo() != null) {
            if (draft.getEffectiveFrom() != null && request.effectiveTo().isBefore(draft.getEffectiveFrom())) {
                throw ApiExceptions.validation("Ngày kết thúc hiệu lực không được trước ngày bắt đầu.", null);
            }
            draft.setEffectiveTo(request.effectiveTo());
        }
        if (request.changeSummary() != null) {
            draft.setChangeSummary(request.changeSummary().trim());
        }

        // Rule 1 & 2: Deposit
        if (request.depositMandatory() != null) draft.setDepositMandatory(request.depositMandatory());
        if (request.depositCalculationMethod() != null) draft.setDepositCalculationMethod(request.depositCalculationMethod().trim());
        if (request.depositMonthlyMultiple() != null) draft.setDepositMonthlyMultiple(request.depositMonthlyMultiple());
        if (request.depositFixedAmount() != null) draft.setDepositFixedAmount(request.depositFixedAmount());
        if (request.depositPercentageRate() != null) draft.setDepositPercentageRate(request.depositPercentageRate());
        if (request.minimumDepositAmount() != null) draft.setMinimumDepositAmount(request.minimumDepositAmount());

        // Rule 3: Refund
        if (request.fullRefundConditions() != null) draft.setFullRefundConditions(request.fullRefundConditions().trim());
        if (request.partialRefundConditions() != null) draft.setPartialRefundConditions(request.partialRefundConditions().trim());
        if (request.noRefundConditions() != null) draft.setNoRefundConditions(request.noRefundConditions().trim());
        if (request.refundProcessingDays() != null) draft.setRefundProcessingDays(request.refundProcessingDays());

        // Rule 4 & 8: Renewal
        if (request.allowEarlyRenewal() != null) draft.setAllowEarlyRenewal(request.allowEarlyRenewal());
        if (request.availableExtensionPeriods() != null) draft.setAvailableExtensionPeriods(request.availableExtensionPeriods().trim());
        if (request.renewalAdvanceNoticeDays() != null) draft.setRenewalAdvanceNoticeDays(request.renewalAdvanceNoticeDays());
        if (request.requireStorageAvailabilityCheck() != null) draft.setRequireStorageAvailabilityCheck(request.requireStorageAvailabilityCheck());
        if (request.renewalDiscountRate() != null) draft.setRenewalDiscountRate(request.renewalDiscountRate());

        // Rule 5: Early Termination
        if (request.allowEarlyTermination() != null) draft.setAllowEarlyTermination(request.allowEarlyTermination());
        if (request.earlyTerminationNoticeDays() != null) draft.setEarlyTerminationNoticeDays(request.earlyTerminationNoticeDays());
        if (request.earlyTerminationFeeCoefficient() != null) draft.setEarlyTerminationFeeCoefficient(request.earlyTerminationFeeCoefficient());
        if (request.earlyTerminationFeeCapMonths() != null) draft.setEarlyTerminationFeeCapMonths(request.earlyTerminationFeeCapMonths());
        if (request.earlyTerminationMinFee() != null) draft.setEarlyTerminationMinFee(request.earlyTerminationMinFee());

        // Rule 6: Late Payment
        draft.setGracePeriodDays(0);
        if (request.latePenaltyCalculationMethod() != null) draft.setLatePenaltyCalculationMethod(request.latePenaltyCalculationMethod().trim());
        if (request.lateFeeAmount() != null) draft.setLateFeeAmount(request.lateFeeAmount());
        if (request.lateFeePercentageRate() != null) draft.setLateFeePercentageRate(request.lateFeePercentageRate());
        if (request.maxOverdueGraceDaysBeforeRepossession() != null) draft.setMaxOverdueGraceDaysBeforeRepossession(request.maxOverdueGraceDaysBeforeRepossession());

        // Rule 7: Deduction
        if (request.deductionCategories() != null) draft.setDeductionCategories(request.deductionCategories().trim());
        if (request.requireEvidenceForDeduction() != null) draft.setRequireEvidenceForDeduction(request.requireEvidenceForDeduction());
        if (request.requireManagerApprovalForDeduction() != null) draft.setRequireManagerApprovalForDeduction(request.requireManagerApprovalForDeduction());
        if (request.excludeNormalWearAndTear() != null) draft.setExcludeNormalWearAndTear(request.excludeNormalWearAndTear());

        // Rule 9: Pricing Protection
        if (request.pricingProtectionEnforced() != null) draft.setPricingProtectionEnforced(request.pricingProtectionEnforced());
        if (request.pricingVersion() != null) draft.setPricingVersion(request.pricingVersion().trim());

        CentralizedPolicy saved = policyRepository.save(draft);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PolicyDiffItem> previewChanges(ActorPrincipal actor, UUID draftId) {
        requirePolicyOwner(actor);
        CentralizedPolicy draft;
        if (draftId != null) {
            draft = policyRepository.findById(draftId)
                .orElseThrow(() -> ApiExceptions.notFound("Bản nháp chính sách không tồn tại"));
        } else {
            draft = policyRepository.findFirstByPublicationStatusOrderByCreatedAtDesc(PublicationStatus.DRAFT)
                .orElseThrow(() -> ApiExceptions.notFound("Chưa có bản nháp chính sách nào"));
        }

        CentralizedPolicy active = getActiveEntity();
        List<PolicyDiffItem> diffs = new ArrayList<>();

        addDiff(diffs, "Quy định chung", "policyVersion", "Phiên bản chính sách", active.getPolicyVersion(), draft.getPolicyVersion());
        addDiff(diffs, "Quy định chung", "effectiveFrom", "Ngày bắt đầu hiệu lực", String.valueOf(active.getEffectiveFrom()), String.valueOf(draft.getEffectiveFrom()));
        addDiff(diffs, "Quy định chung", "effectiveTo", "Ngày kết thúc hiệu lực", String.valueOf(active.getEffectiveTo()), String.valueOf(draft.getEffectiveTo()));

        // Rule 1 & 2
        addDiff(diffs, "Tiền cọc đảm bảo thuê", "depositMandatory", "Bắt buộc đặt cọc", String.valueOf(active.isDepositMandatory()), String.valueOf(draft.isDepositMandatory()));
        addDiff(diffs, "Tiền cọc đảm bảo thuê", "depositCalculationMethod", "Phương thức tính cọc", active.getDepositCalculationMethod(), draft.getDepositCalculationMethod());
        addDiff(diffs, "Tiền cọc đảm bảo thuê", "depositMonthlyMultiple", "Hệ số tháng cọc", String.valueOf(active.getDepositMonthlyMultiple()), String.valueOf(draft.getDepositMonthlyMultiple()));
        addDiff(diffs, "Tiền cọc đảm bảo thuê", "depositFixedAmount", "Mức cọc cố định (VND)", String.valueOf(active.getDepositFixedAmount()), String.valueOf(draft.getDepositFixedAmount()));
        addDiff(diffs, "Tiền cọc đảm bảo thuê", "depositPercentageRate", "Tỷ lệ cọc (% hợp đồng)", String.valueOf(active.getDepositPercentageRate()), String.valueOf(draft.getDepositPercentageRate()));
        addDiff(diffs, "Tiền cọc đảm bảo thuê", "minimumDepositAmount", "Mức cọc tối thiểu (VND)", String.valueOf(active.getMinimumDepositAmount()), String.valueOf(draft.getMinimumDepositAmount()));

        // Rule 3
        addDiff(diffs, "Hoàn trả tiền cọc", "refundProcessingDays", "Thời hạn hoàn tiền (ngày)", String.valueOf(active.getRefundProcessingDays()), String.valueOf(draft.getRefundProcessingDays()));

        // Rule 4 & 8
        addDiff(diffs, "Gia hạn hợp đồng", "allowEarlyRenewal", "Cho phép gia hạn hợp đồng", String.valueOf(active.isAllowEarlyRenewal()), String.valueOf(draft.isAllowEarlyRenewal()));
        addDiff(diffs, "Gia hạn hợp đồng", "availableExtensionPeriods", "Các kỳ hạn gia hạn cho phép (tháng)", active.getAvailableExtensionPeriods(), draft.getAvailableExtensionPeriods());
        addDiff(diffs, "Gia hạn hợp đồng", "renewalAdvanceNoticeDays", "Thời gian báo trước gia hạn (ngày)", String.valueOf(active.getRenewalAdvanceNoticeDays()), String.valueOf(draft.getRenewalAdvanceNoticeDays()));
        addDiff(diffs, "Gia hạn hợp đồng", "renewalDiscountRate", "Tỷ lệ giảm giá gia hạn", String.valueOf(active.getRenewalDiscountRate()), String.valueOf(draft.getRenewalDiscountRate()));

        // Rule 5
        addDiff(diffs, "Chấm dứt trước hạn", "allowEarlyTermination", "Cho phép chấm dứt trước hạn", String.valueOf(active.isAllowEarlyTermination()), String.valueOf(draft.isAllowEarlyTermination()));
        addDiff(diffs, "Chấm dứt trước hạn", "earlyTerminationNoticeDays", "Thời gian báo trước (ngày)", String.valueOf(active.getEarlyTerminationNoticeDays()), String.valueOf(draft.getEarlyTerminationNoticeDays()));
        addDiff(diffs, "Chấm dứt trước hạn", "earlyTerminationFeeCoefficient", "Hệ số phạt chấm dứt trước hạn", String.valueOf(active.getEarlyTerminationFeeCoefficient()), String.valueOf(draft.getEarlyTerminationFeeCoefficient()));
        addDiff(diffs, "Chấm dứt trước hạn", "earlyTerminationFeeCapMonths", "Mức trần phí phạt (tháng)", String.valueOf(active.getEarlyTerminationFeeCapMonths()), String.valueOf(draft.getEarlyTerminationFeeCapMonths()));

        // Rule 6
        addDiff(diffs, "Phạt thanh toán chậm", "gracePeriodDays", "Thời gian ân hạn (ngày)", String.valueOf(active.getGracePeriodDays()), String.valueOf(draft.getGracePeriodDays()));
        addDiff(diffs, "Phạt thanh toán chậm", "latePenaltyCalculationMethod", "Phương thức tính phạt", active.getLatePenaltyCalculationMethod(), draft.getLatePenaltyCalculationMethod());
        addDiff(diffs, "Phạt thanh toán chậm", "lateFeeAmount", "Mức phạt cố định/ngày (VND)", String.valueOf(active.getLateFeeAmount()), String.valueOf(draft.getLateFeeAmount()));
        addDiff(diffs, "Phạt thanh toán chậm", "lateFeePercentageRate", "Tỷ lệ phạt ngày (%)", String.valueOf(active.getLateFeePercentageRate()), String.valueOf(draft.getLateFeePercentageRate()));

        // Rule 7
        addDiff(diffs, "Khấu trừ tiền cọc", "requireEvidenceForDeduction", "Bắt buộc biên bản/bằng chứng", String.valueOf(active.isRequireEvidenceForDeduction()), String.valueOf(draft.isRequireEvidenceForDeduction()));
        addDiff(diffs, "Khấu trừ tiền cọc", "excludeNormalWearAndTear", "Miễn trừ hao mòn tự nhiên", String.valueOf(active.isExcludeNormalWearAndTear()), String.valueOf(draft.isExcludeNormalWearAndTear()));

        // Rule 9
        addDiff(diffs, "Bảo vệ biểu giá", "pricingProtectionEnforced", "Bảo vệ giá hợp đồng đang chạy", String.valueOf(active.isPricingProtectionEnforced()), String.valueOf(draft.isPricingProtectionEnforced()));
        addDiff(diffs, "Bảo vệ biểu giá", "pricingVersion", "Mã phiên bản biểu giá", active.getPricingVersion(), draft.getPricingVersion());

        return diffs;
    }

    @Transactional
    public CentralizedPolicyResponse publishPolicy(ActorPrincipal actor, UUID draftId, PublishCentralizedPolicyRequest request) {
        requirePolicyOwner(actor);

        CentralizedPolicy draft;
        if (draftId != null) {
            draft = policyRepository.findById(draftId)
                .orElseThrow(() -> ApiExceptions.notFound("Bản nháp chính sách không tồn tại"));
        } else {
            draft = policyRepository.findFirstByPublicationStatusOrderByCreatedAtDesc(PublicationStatus.DRAFT)
                .orElseThrow(() -> ApiExceptions.notFound("Chưa có bản nháp chính sách nào để ban hành"));
        }

        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw ApiExceptions.validation("Ngày kết thúc hiệu lực không được trước ngày bắt đầu.", null);
        }

        User publisher = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("Người dùng không tồn tại"));

        CentralizedPolicy before = getActiveEntity();

        LocalDate today = LocalDate.now();
        draft.setEffectiveFrom(request.effectiveFrom());
        draft.setEffectiveTo(request.effectiveTo());
        draft.setChangeSummary(request.changeSummary().trim());
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(publisher);
        draft.setPublicationStatus(PublicationStatus.PUBLISHED);

        boolean isEffectiveNow = !request.effectiveFrom().isAfter(today) && (request.effectiveTo() == null || !request.effectiveTo().isBefore(today));
        draft.setActive(isEffectiveNow);

        if (isEffectiveNow) {
            // Archive previous active policies
            List<CentralizedPolicy> previousActives = policyRepository.findActivePolicies();
            for (CentralizedPolicy prev : previousActives) {
                if (!prev.getId().equals(draft.getId())) {
                    prev.setActive(false);
                    prev.setPublicationStatus(PublicationStatus.ARCHIVED);
                    if (prev.getEffectiveTo() == null || prev.getEffectiveTo().isAfter(today)) {
                        prev.setEffectiveTo(request.effectiveFrom().minusDays(1));
                    }
                    policyRepository.save(prev);
                }
            }
        }

        CentralizedPolicy saved = policyRepository.save(draft);

        // Sync legacy system settings for seamless backwards compatibility
        syncSystemSettings(saved);

        CentralizedPolicyResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "CENTRALIZED_POLICY_PUBLISHED",
            "CentralizedPolicy",
            saved.getId(),
            null,
            toResponse(before),
            after
        );

        return after;
    }

    @Transactional
    public CentralizedPolicyResponse rollbackToVersion(ActorPrincipal actor, UUID versionId) {
        requirePolicyOwner(actor);
        CentralizedPolicy target = policyRepository.findById(versionId)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy phiên bản chính sách"));

        CentralizedPolicy draft = policyRepository.findFirstByPublicationStatusOrderByCreatedAtDesc(PublicationStatus.DRAFT)
            .orElseGet(CentralizedPolicy::new);

        draft.setPolicyVersion(generateNextVersionString());
        draft.setPublicationStatus(PublicationStatus.DRAFT);
        draft.setActive(false);
        draft.setEffectiveFrom(LocalDate.now());
        draft.setEffectiveTo(null);
        draft.setChangeSummary("Khôi phục cấu hình từ phiên bản " + target.getPolicyVersion());

        copyPolicyValues(target, draft);

        CentralizedPolicy saved = policyRepository.save(draft);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CentralizedPolicyResponse> listPublicationHistory() {
        return policyRepository.findAllByOrderByCreatedAtDesc().stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public CentralizedPolicyResponse getPolicyVersion(UUID id) {
        CentralizedPolicy policy = policyRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy phiên bản chính sách"));
        return toResponse(policy);
    }

    @Transactional(readOnly = true)
    public PolicyCalculationSimulationResponse simulateCalculation(PolicyCalculationSimulationRequest request) {
        CentralizedPolicy policy = getActiveEntity();

        BigDecimal monthlyRent = request.monthlyRent() != null && request.monthlyRent().signum() > 0
            ? request.monthlyRent() : new BigDecimal("3000000.00");
        int rentalMonths = request.rentalMonths() > 0 ? request.rentalMonths() : 6;
        int overdueDays = Math.max(0, request.overdueDays());

        BigDecimal totalRent = monthlyRent.multiply(BigDecimal.valueOf(rentalMonths)).setScale(2, RoundingMode.HALF_UP);

        // 1. Deposit calculation
        BigDecimal calculatedDeposit;
        String depositFormula;
        String method = policy.getDepositCalculationMethod();
        if ("MONTHLY_RENT_MULTIPLE".equalsIgnoreCase(method)) {
            BigDecimal multiple = policy.getDepositMonthlyMultiple() != null ? policy.getDepositMonthlyMultiple() : BigDecimal.ONE;
            calculatedDeposit = monthlyRent.multiply(multiple).setScale(2, RoundingMode.HALF_UP);
            depositFormula = String.format("%s tháng tiền thuê (%s × %s)", multiple.stripTrailingZeros().toPlainString(), formatVndProse(monthlyRent), multiple.stripTrailingZeros().toPlainString());
        } else if ("FIXED_AMOUNT".equalsIgnoreCase(method)) {
            calculatedDeposit = policy.getDepositFixedAmount() != null ? policy.getDepositFixedAmount() : new BigDecimal("2000000.00");
            depositFormula = String.format("Mức cọc cố định toàn hệ thống (%s)", formatVndProse(calculatedDeposit));
        } else { // PERCENTAGE_OF_TOTAL_RENT
            BigDecimal rate = policy.getDepositPercentageRate() != null ? policy.getDepositPercentageRate() : new BigDecimal("0.2000");
            calculatedDeposit = totalRent.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            depositFormula = String.format("%s%% tổng giá trị hợp đồng (%s × %s%%)", rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(), formatVndProse(totalRent), rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString());
        }

        if (policy.getMinimumDepositAmount() != null && calculatedDeposit.compareTo(policy.getMinimumDepositAmount()) < 0) {
            calculatedDeposit = policy.getMinimumDepositAmount();
            depositFormula += String.format(" (Áp dụng mức sàn tối thiểu: %s)", formatVndProse(policy.getMinimumDepositAmount()));
        }

        // 2. Late penalty calculation
        int grace = 0;
        int chargeableOverdue = Math.max(0, overdueDays - grace);
        BigDecimal penalty;
        String penaltyFormula;
        String penaltyMethod = "PERCENTAGE_DAILY_RATE";

        if (chargeableOverdue == 0) {
            penalty = BigDecimal.ZERO;
            penaltyFormula = overdueDays <= grace
                ? String.format("Trong thời gian ân hạn %d ngày (không tính phạt)", grace)
                : "Không có ngày quá hạn";
        } else {
            if ("PERCENTAGE_DAILY_RATE".equalsIgnoreCase(penaltyMethod)) {
                BigDecimal dailyRent = monthlyRent.divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
                BigDecimal dailyRate = BigDecimal.ONE;
                BigDecimal dailyFee = dailyRent.multiply(dailyRate).multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP);
                penalty = dailyFee.multiply(BigDecimal.valueOf(chargeableOverdue)).setScale(2, RoundingMode.HALF_UP);
                penaltyFormula = String.format("%d ngày tính phạt (ân hạn %d ngày) × (%s%% × %s đ/ngày × 1,5) = %s",
                    chargeableOverdue, grace, dailyRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(), formatVndProse(dailyRent), formatVndProse(penalty));
            } else { // FIXED_DAILY_AMOUNT
                BigDecimal dailyRate = policy.getLateFeeAmount() != null ? policy.getLateFeeAmount() : new BigDecimal("50000.00");
                penalty = dailyRate.multiply(BigDecimal.valueOf(chargeableOverdue)).setScale(2, RoundingMode.HALF_UP);
                penaltyFormula = String.format("%d ngày tính phạt (sau ân hạn %d ngày) × %s đ/ngày = %s",
                    chargeableOverdue, grace, formatVndProse(dailyRate), formatVndProse(penalty));
            }
        }

        return new PolicyCalculationSimulationResponse(
            monthlyRent,
            rentalMonths,
            totalRent,
            method,
            calculatedDeposit,
            depositFormula,
            overdueDays,
            grace,
            chargeableOverdue,
            penaltyMethod,
            penalty,
            penaltyFormula
        );
    }

    public BigDecimal calculateDeposit(BigDecimal monthlyRent, BigDecimal totalRent) {
        CentralizedPolicy policy = getActiveEntity();
        if (monthlyRent == null || monthlyRent.signum() <= 0) return BigDecimal.ZERO;
        String method = policy.getDepositCalculationMethod();
        BigDecimal deposit;
        if ("FIXED_AMOUNT".equalsIgnoreCase(method)) {
            deposit = policy.getDepositFixedAmount() != null ? policy.getDepositFixedAmount() : monthlyRent;
        } else if ("PERCENTAGE_OF_TOTAL_RENT".equalsIgnoreCase(method)) {
            BigDecimal rate = policy.getDepositPercentageRate() != null ? policy.getDepositPercentageRate() : new BigDecimal("0.2000");
            BigDecimal base = totalRent != null && totalRent.signum() > 0 ? totalRent : monthlyRent;
            deposit = base.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        } else { // MONTHLY_RENT_MULTIPLE
            BigDecimal multiple = policy.getDepositMonthlyMultiple() != null ? policy.getDepositMonthlyMultiple() : BigDecimal.ONE;
            deposit = monthlyRent.multiply(multiple).setScale(2, RoundingMode.HALF_UP);
        }

        if (policy.getMinimumDepositAmount() != null && deposit.compareTo(policy.getMinimumDepositAmount()) < 0) {
            deposit = policy.getMinimumDepositAmount();
        }
        return deposit;
    }

    public BigDecimal calculateLatePenalty(BigDecimal monthlyRent, int overdueDays) {
        CentralizedPolicy policy = getActiveEntity();
        if (overdueDays <= 0 || monthlyRent == null || monthlyRent.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int chargeableDays = overdueDays;
        if (true) {
            BigDecimal dailyRent = monthlyRent.divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
            BigDecimal rate = BigDecimal.ONE;
            return dailyRent.multiply(rate).multiply(new BigDecimal("1.5")).multiply(BigDecimal.valueOf(chargeableDays)).setScale(2, RoundingMode.HALF_UP);
        } else {
            BigDecimal dailyFee = policy.getLateFeeAmount() != null ? policy.getLateFeeAmount() : new BigDecimal("50000.00");
            return dailyFee.multiply(BigDecimal.valueOf(chargeableDays)).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private void syncSystemSettings(CentralizedPolicy policy) {
        saveSetting("gracePeriod", "0");
        saveSetting("lateFeeAmount", policy.getLateFeeAmount().toPlainString());
        saveSetting("defaultDepositRatio", policy.getDepositPercentageRate().toPlainString());
    }

    private void saveSetting(String key, String value) {
        Optional<SystemSetting> opt = systemSettingRepository.findBySettingKey(key);
        SystemSetting setting = opt.orElseGet(() -> {
            SystemSetting s = new SystemSetting();
            s.setSettingKey(key);
            s.setGroupName("Billing & Invoicing Rules");
            s.setLabel(key);
            s.setSettingType("text");
            return s;
        });
        setting.setValue(value);
        systemSettingRepository.save(setting);
    }

    private void addDiff(List<PolicyDiffItem> diffs, String category, String key, String label, String oldVal, String newVal) {
        boolean changed = !Objects.equals(oldVal, newVal);
        diffs.add(new PolicyDiffItem(category, key, label, oldVal != null ? oldVal : "-", newVal != null ? newVal : "-", changed));
    }

    private String formatVndProse(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,d", amount.longValue());
    }

    private String generateNextVersionString() {
        long count = policyRepository.count();
        return String.format("POL-%d.%d", LocalDate.now().getYear(), count + 1);
    }

    private void copyPolicyValues(CentralizedPolicy src, CentralizedPolicy dst) {
        dst.setDepositMandatory(src.isDepositMandatory());
        dst.setDepositCalculationMethod(src.getDepositCalculationMethod());
        dst.setDepositMonthlyMultiple(src.getDepositMonthlyMultiple());
        dst.setDepositFixedAmount(src.getDepositFixedAmount());
        dst.setDepositPercentageRate(src.getDepositPercentageRate());
        dst.setMinimumDepositAmount(src.getMinimumDepositAmount());

        dst.setFullRefundConditions(src.getFullRefundConditions());
        dst.setPartialRefundConditions(src.getPartialRefundConditions());
        dst.setNoRefundConditions(src.getNoRefundConditions());
        dst.setRefundProcessingDays(src.getRefundProcessingDays());

        dst.setAllowEarlyRenewal(src.isAllowEarlyRenewal());
        dst.setAvailableExtensionPeriods(src.getAvailableExtensionPeriods());
        dst.setRenewalAdvanceNoticeDays(src.getRenewalAdvanceNoticeDays());
        dst.setRequireStorageAvailabilityCheck(src.isRequireStorageAvailabilityCheck());
        dst.setRenewalDiscountRate(src.getRenewalDiscountRate());

        dst.setAllowEarlyTermination(src.isAllowEarlyTermination());
        dst.setEarlyTerminationNoticeDays(src.getEarlyTerminationNoticeDays());
        dst.setEarlyTerminationFeeCoefficient(src.getEarlyTerminationFeeCoefficient());
        dst.setEarlyTerminationFeeCapMonths(src.getEarlyTerminationFeeCapMonths());
        dst.setEarlyTerminationMinFee(src.getEarlyTerminationMinFee());

        dst.setGracePeriodDays(src.getGracePeriodDays());
        dst.setLatePenaltyCalculationMethod(src.getLatePenaltyCalculationMethod());
        dst.setLateFeeAmount(src.getLateFeeAmount());
        dst.setLateFeePercentageRate(src.getLateFeePercentageRate());
        dst.setMaxOverdueGraceDaysBeforeRepossession(src.getMaxOverdueGraceDaysBeforeRepossession());

        dst.setDeductionCategories(src.getDeductionCategories());
        dst.setRequireEvidenceForDeduction(src.isRequireEvidenceForDeduction());
        dst.setRequireManagerApprovalForDeduction(src.isRequireManagerApprovalForDeduction());
        dst.setExcludeNormalWearAndTear(src.isExcludeNormalWearAndTear());

        dst.setPricingProtectionEnforced(src.isPricingProtectionEnforced());
        dst.setPricingVersion(src.getPricingVersion());
    }

    private CentralizedPolicy seedDefaultPolicy() {
        CentralizedPolicy p = new CentralizedPolicy();
        p.setPolicyVersion("POL-2026.1");
        p.setPublicationStatus(PublicationStatus.PUBLISHED);
        p.setPublishedAt(Instant.now());
        p.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        p.setEffectiveTo(null);
        p.setActive(true);
        p.setChangeSummary("Phiên bản chính sách vận hành tiêu chuẩn ban hành toàn hệ thống StorageHub.");

        p.setDepositMandatory(true);
        p.setDepositCalculationMethod("MONTHLY_RENT_MULTIPLE");
        p.setDepositMonthlyMultiple(BigDecimal.valueOf(1.00));
        p.setDepositFixedAmount(new BigDecimal("2000000.00"));
        p.setDepositPercentageRate(new BigDecimal("0.2000"));
        p.setMinimumDepositAmount(new BigDecimal("500000.00"));

        p.setFullRefundConditions("Hoàn lại 100% tiền cọc khi khách hàng trả kho đúng hạn hợp đồng (hoặc có thông báo trước theo quy định), hoàn tất bàn giao mặt bằng nguyên trạng, trả đủ chìa khóa/thẻ từ và không có bất kỳ khoản nợ phí lưu kho hay dịch vụ nào phát sinh.");
        p.setPartialRefundConditions("Khấu trừ một phần tiền cọc để bù đắp các nghĩa vụ chưa hoàn thành (tiền thuê trễ hạn, tiền phạt quá hạn, phí dịch vụ phát sinh, phí sửa chữa hư hại trang thiết bị kho do khách gây ra, phí cấp lại thẻ/khóa bị mất). Số tiền cọc còn lại sau khi khấu trừ sẽ được hoàn trả cho khách hàng trong thời hạn quy định.");
        p.setNoRefundConditions("Tiền cọc không được hoàn lại trong các trường hợp: Tổng chi phí khấu trừ và công nợ vượt quá giá trị tiền cọc; khách hàng tự ý bỏ kho không thông báo quá thời hạn ân hạn tối đa; vi phạm nghiêm trọng quy chuẩn an toàn lưu kho (chứa hàng cấm, gây hỏa hoạn, hủy hoại tài sản cơ sở).");
        p.setRefundProcessingDays(7);

        p.setAllowEarlyRenewal(true);
        p.setAvailableExtensionPeriods("1,3,6,12,24");
        p.setRenewalAdvanceNoticeDays(15);
        p.setRequireStorageAvailabilityCheck(true);
        p.setRenewalDiscountRate(new BigDecimal("0.0500"));

        p.setAllowEarlyTermination(true);
        p.setEarlyTerminationNoticeDays(14);
        p.setEarlyTerminationFeeCoefficient(BigDecimal.ONE);
        p.setEarlyTerminationFeeCapMonths(1);
        p.setEarlyTerminationMinFee(new BigDecimal("500000.00"));

        p.setGracePeriodDays(0);
        p.setLatePenaltyCalculationMethod("PERCENTAGE_DAILY_RATE");
        p.setLateFeeAmount(new BigDecimal("50000.00"));
        p.setLateFeePercentageRate(BigDecimal.ONE);
        p.setMaxOverdueGraceDaysBeforeRepossession(7);

        p.setDeductionCategories("[{\"code\":\"UNPAID_RENT\",\"name\":\"Tiền thuê kho quá hạn chưa thanh toán\",\"description\":\"Tiền thuê tính theo số ngày thực tế sử dụng chưa tất toán\"},{\"code\":\"UNPAID_SERVICES\",\"name\":\"Phí dịch vụ phụ trợ\",\"description\":\"Các dịch vụ phát sinh như bốc xếp, lưu pallet, hỗ trợ kỹ thuật\"},{\"code\":\"UNPAID_UTILITIES\",\"name\":\"Chi phí tiện ích phát sinh\",\"description\":\"Điện nước hoặc phụ phí môi trường theo hợp đồng\"},{\"code\":\"CUSTOMER_DAMAGE\",\"name\":\"Chi phí bồi thường hư hại cơ sở vật chất\",\"description\":\"Hư hỏng vách ngăn, cửa cuốn, đèn, sàn kho do lỗi khách hàng (loại trừ hao mòn tự nhiên)\"},{\"code\":\"LOST_KEYS_LOCKS\",\"name\":\"Phí thay khóa / cấp lại thẻ từ\",\"description\":\"Thẻ từ ra vào hoặc chìa khóa kho bị mất hoặc hư hỏng nặng\"}]");
        p.setRequireEvidenceForDeduction(true);
        p.setRequireManagerApprovalForDeduction(true);
        p.setExcludeNormalWearAndTear(true);

        p.setPricingProtectionEnforced(true);
        p.setPricingVersion("PRICE-2026.1");

        return policyRepository.save(p);
    }

    private void requirePolicyOwner(ActorPrincipal actor) {
        if (actor == null) throw ApiExceptions.unauthorized("Authentication is required");
        if (!actor.hasRole(RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Chỉ Giám Đốc Thương Mại (Business Operations) mới có quyền quản lý và ban hành chính sách");
        }
        authorizationService.require(actor, SystemPermission.MANAGE_POLICIES);
    }

    public CentralizedPolicyResponse toResponse(CentralizedPolicy p) {
        PublicationStatus pub = p.getPublicationStatus();
        String runtimeStatus;
        if (pub == PublicationStatus.DRAFT) {
            runtimeStatus = "DRAFT";
        } else if (pub == PublicationStatus.ARCHIVED || pub == PublicationStatus.INACTIVE) {
            runtimeStatus = "ARCHIVED";
        } else {
            LocalDate now = LocalDate.now();
            if (p.getEffectiveFrom() != null && p.getEffectiveFrom().isAfter(now)) {
                runtimeStatus = "SCHEDULED";
            } else if (p.getEffectiveTo() != null && p.getEffectiveTo().isBefore(now)) {
                runtimeStatus = "EXPIRED";
            } else {
                runtimeStatus = "ACTIVE";
            }
        }

        List<Integer> periods = new ArrayList<>();
        if (p.getAvailableExtensionPeriods() != null) {
            Arrays.stream(p.getAvailableExtensionPeriods().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> {
                    try { periods.add(Integer.parseInt(s)); } catch (Exception ignored) {}
                });
        }

        return new CentralizedPolicyResponse(
            p.getId(),
            p.getPolicyVersion(),
            p.getPublicationStatus(),
            runtimeStatus,
            p.getPublishedAt(),
            p.getPublishedBy() != null ? p.getPublishedBy().getId() : null,
            p.getPublishedBy() != null ? p.getPublishedBy().getFullName() : "Ban Quản Trị Hệ Thống",
            p.getEffectiveFrom(),
            p.getEffectiveTo(),
            p.isActive(),
            p.getChangeSummary(),

            p.isDepositMandatory(),
            p.getDepositCalculationMethod(),
            p.getDepositMonthlyMultiple(),
            p.getDepositFixedAmount(),
            p.getDepositPercentageRate(),
            p.getMinimumDepositAmount(),

            p.getFullRefundConditions(),
            p.getPartialRefundConditions(),
            p.getNoRefundConditions(),
            p.getRefundProcessingDays(),

            p.isAllowEarlyRenewal(),
            periods,
            p.getRenewalAdvanceNoticeDays(),
            p.isRequireStorageAvailabilityCheck(),
            p.getRenewalDiscountRate(),

            p.isAllowEarlyTermination(),
            p.getEarlyTerminationNoticeDays(),
            p.getEarlyTerminationFeeCoefficient(),
            p.getEarlyTerminationFeeCapMonths(),
            p.getEarlyTerminationMinFee(),

            p.getGracePeriodDays(),
            p.getLatePenaltyCalculationMethod(),
            p.getLateFeeAmount(),
            p.getLateFeePercentageRate(),
            p.getMaxOverdueGraceDaysBeforeRepossession(),

            p.getDeductionCategories(),
            p.isRequireEvidenceForDeduction(),
            p.isRequireManagerApprovalForDeduction(),
            p.isExcludeNormalWearAndTear(),

            p.isPricingProtectionEnforced(),
            p.getPricingVersion(),

            p.getCreatedAt(),
            p.getUpdatedAt()
        );
    }
}
