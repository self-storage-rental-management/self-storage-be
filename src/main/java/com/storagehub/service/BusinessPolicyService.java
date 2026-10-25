package com.storagehub.service;

import com.storagehub.api.configuration.BusinessConfigResponse;
import com.storagehub.api.configuration.RentalPackagePolicyRequest;
import com.storagehub.api.configuration.RentalPackagePolicyResponse;
import com.storagehub.api.configuration.UpdateBusinessConfigRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.PublicationStatus;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class BusinessPolicyService {

    private final RentalPackagePolicyRepository policyRepository;
    private final FacilityRepository facilityRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

    public BusinessPolicyService(
        RentalPackagePolicyRepository policyRepository,
        FacilityRepository facilityRepository,
        SystemSettingRepository systemSettingRepository,
        AdminAuthorizationService authorizationService,
        FacilityScopeService facilityScopeService,
        AuditLogService auditLogService
    ) {
        this.policyRepository = policyRepository;
        this.facilityRepository = facilityRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.authorizationService = authorizationService;
        this.facilityScopeService = facilityScopeService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public BusinessConfigResponse getBusinessConfig(ActorPrincipal actor) {
        authorizationService.require(actor, SystemPermission.VIEW_POLICIES);

        int gracePeriodDays = getIntSetting("gracePeriod", 3);
        BigDecimal lateFeeAmount = getBigDecimalSetting("lateFeeAmount", new BigDecimal("650000"));
        BigDecimal defaultDepositRatio = getBigDecimalSetting("defaultDepositRatio", new BigDecimal("0.20"));
        double holdExpiryHours = getDoubleSetting("holdExpiryHours", 2.0);
        int dimDivisor = getIntSetting("dimDivisor", 5000);
        boolean maintenanceMode = getBooleanSetting("maintenanceMode", false);
        String bannerNotice = getStringSetting("bannerNotice", "");
        int autoInvoiceDays = getIntSetting("autoInvoiceDays", 7);
        boolean autoProrate = getBooleanSetting("autoProrate", true);

        return new BusinessConfigResponse(
            gracePeriodDays,
            lateFeeAmount,
            defaultDepositRatio,
            holdExpiryHours,
            dimDivisor,
            maintenanceMode,
            bannerNotice,
            autoInvoiceDays,
            autoProrate
        );
    }

    @Transactional
    public BusinessConfigResponse updateBusinessConfig(ActorPrincipal actor, UpdateBusinessConfigRequest request) {
        requirePolicyOwner(actor);

        BusinessConfigResponse before = getBusinessConfig(actor);

        if (request.gracePeriodDays() != null) {
            saveSetting("gracePeriod", String.valueOf(request.gracePeriodDays()));
        }
        if (request.lateFeeAmount() != null) {
            saveSetting("lateFeeAmount", request.lateFeeAmount().toPlainString());
        }
        if (request.defaultDepositRatio() != null) {
            saveSetting("defaultDepositRatio", request.defaultDepositRatio().toPlainString());
        }
        if (request.holdExpiryHours() != null) {
            saveSetting("holdExpiryHours", String.valueOf(request.holdExpiryHours()));
        }
        if (request.dimDivisor() != null) {
            saveSetting("dimDivisor", String.valueOf(request.dimDivisor()));
        }
        if (request.maintenanceMode() != null) {
            saveSetting("maintenanceMode", String.valueOf(request.maintenanceMode()));
        }
        if (request.bannerNotice() != null) {
            saveSetting("bannerNotice", "\"" + request.bannerNotice().trim() + "\"");
        }
        if (request.autoInvoiceDays() != null) {
            saveSetting("autoInvoiceDays", String.valueOf(request.autoInvoiceDays()));
        }
        if (request.autoProrate() != null) {
            saveSetting("autoProrate", String.valueOf(request.autoProrate()));
        }

        BusinessConfigResponse after = getBusinessConfig(actor);

        auditLogService.recordMutation(
            "BUSINESS_CONFIG_UPDATED",
            "BusinessConfig",
            null,
            null,
            before,
            after
        );

        return after;
    }

    @Transactional(readOnly = true)
    public List<RentalPackagePolicyResponse> listPackagePolicies(
        ActorPrincipal actor,
        UUID facilityId,
        Boolean activeOnly
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_POLICIES);

        List<RentalPackagePolicy> policies;
        if (facilityId != null) {
            if (activeOnly != null && activeOnly) {
                policies = policyRepository.findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
                    facilityId, LocalDate.now()
                );
            } else {
                policies = policyRepository.findByFacility_IdOrderByRentalMonthsAsc(facilityId);
            }
        } else {
            policies = policyRepository.findAllByOrderByRentalMonthsAsc();
            if (activeOnly != null && activeOnly) {
                policies = policies.stream().filter(p -> p.isActive()).toList();
            }
        }

        return policies.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RentalPackagePolicyResponse getPackagePolicy(ActorPrincipal actor, UUID policyId) {
        authorizationService.require(actor, SystemPermission.VIEW_POLICIES);
        RentalPackagePolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental package policy not found"));
        return toResponse(policy);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RentalPackagePolicyResponse createPackagePolicy(ActorPrincipal actor, RentalPackagePolicyRequest request) {
        requirePolicyOwner(actor);

        Facility facility = facilityRepository.findById(request.facilityId())
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));

        facilityScopeService.assertCanManage(actor, facility.getId());

        if (policyRepository.findByFacility_IdAndCode(facility.getId(), request.code().trim()).isPresent()) {
            throw ApiExceptions.conflict("A package policy with code '" + request.code() + "' already exists for this facility");
        }
        validatePackagePeriod(request, facility.getId(), null);

        int months = request.resolveRentalMonths();
        Integer years = request.rentalYears() != null ? request.rentalYears() : (months % 12 == 0 ? months / 12 : null);
        PublicationStatus pubStatus = request.publicationStatus() != null ? request.publicationStatus() : PublicationStatus.DRAFT;

        RentalPackagePolicy policy = new RentalPackagePolicy();
        policy.setFacility(facility);
        policy.setCode(request.code().trim());
        policy.setName(request.name().trim());
        policy.setRentalMonths(months);
        policy.setFreeSetupDays(ReservationQuoteService.defaultFreeSetupDays(months));
        policy.setRentalYears(years);
        policy.setDescription(request.description());
        policy.setDiscountRate(request.discountRate());
        policy.setDepositRate(request.depositRate() != null ? request.depositRate() : new BigDecimal("0.2000"));
        policy.setPolicyVersion(request.policyVersion().trim());
        policy.setPublicationStatus(pubStatus);

        LocalDate now = LocalDate.now();
        if (pubStatus == PublicationStatus.PUBLISHED) {
            boolean activeWindow = !request.effectiveFrom().isAfter(now) && (request.effectiveTo() == null || !request.effectiveTo().isBefore(now));
            policy.setActive(request.active() == null ? activeWindow : (request.active() && activeWindow));
        } else {
            policy.setActive(false);
        }

        policy.setEffectiveFrom(request.effectiveFrom());
        policy.setEffectiveTo(request.effectiveTo());

        RentalPackagePolicy saved = policyRepository.save(policy);

        auditLogService.recordMutation(
            "RENTAL_PACKAGE_POLICY_CREATED",
            "RentalPackagePolicy",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RentalPackagePolicyResponse updatePackagePolicy(
        ActorPrincipal actor,
        UUID policyId,
        RentalPackagePolicyRequest request
    ) {
        requirePolicyOwner(actor);

        RentalPackagePolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental package policy not found"));

        facilityScopeService.assertCanManage(actor, policy.getFacility().getId());

        if (policy.getPublicationStatus() == PublicationStatus.PUBLISHED) {
            throw ApiExceptions.validation("Cấu hình đã xuất bản không được chỉnh sửa trực tiếp. Vui lòng chuyển trạng thái về INACTIVE trước khi chỉnh sửa.", null);
        }

        validatePackagePeriod(request, policy.getFacility().getId(), policy.getId());

        RentalPackagePolicyResponse before = toResponse(policy);

        int months = request.resolveRentalMonths();
        Integer years = request.rentalYears() != null ? request.rentalYears() : (months % 12 == 0 ? months / 12 : null);

        policy.setName(request.name().trim());
        policy.setRentalMonths(months);
        policy.setFreeSetupDays(ReservationQuoteService.defaultFreeSetupDays(months));
        policy.setRentalYears(years);
        policy.setDescription(request.description());
        policy.setDiscountRate(request.discountRate());
        if (request.depositRate() != null) {
            policy.setDepositRate(request.depositRate());
        }
        policy.setPolicyVersion(request.policyVersion().trim());
        if (request.publicationStatus() != null && request.publicationStatus() != PublicationStatus.PUBLISHED) {
            policy.setPublicationStatus(request.publicationStatus());
        }
        policy.setActive(false);
        policy.setEffectiveFrom(request.effectiveFrom());
        policy.setEffectiveTo(request.effectiveTo());

        RentalPackagePolicy saved = policyRepository.save(policy);
        RentalPackagePolicyResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "RENTAL_PACKAGE_POLICY_UPDATED",
            "RentalPackagePolicy",
            saved.getId(),
            saved.getFacility().getId(),
            before,
            after
        );

        return after;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RentalPackagePolicyResponse publishPackagePolicy(ActorPrincipal actor, UUID policyId) {
        requirePolicyOwner(actor);

        RentalPackagePolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental package policy not found"));

        facilityScopeService.assertCanManage(actor, policy.getFacility().getId());

        RentalPackagePolicyResponse before = toResponse(policy);

        RentalPackagePolicyRequest validateReq = new RentalPackagePolicyRequest(
            policy.getFacility().getId(),
            policy.getCode(),
            policy.getName(),
            policy.getRentalMonths(),
            policy.getRentalYears(),
            policy.getDescription(),
            policy.getDiscountRate(),
            policy.getPolicyVersion(),
            PublicationStatus.PUBLISHED,
            true,
            policy.getEffectiveFrom(),
            policy.getEffectiveTo()
        );
        validatePackagePeriod(validateReq, policy.getFacility().getId(), policy.getId());

        policy.setPublicationStatus(PublicationStatus.PUBLISHED);
        LocalDate now = LocalDate.now();
        boolean isActiveWindow = !policy.getEffectiveFrom().isAfter(now)
            && (policy.getEffectiveTo() == null || !policy.getEffectiveTo().isBefore(now));
        policy.setActive(isActiveWindow);

        RentalPackagePolicy saved = policyRepository.save(policy);
        RentalPackagePolicyResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "RENTAL_PACKAGE_POLICY_PUBLISHED",
            "RentalPackagePolicy",
            saved.getId(),
            saved.getFacility().getId(),
            before,
            after
        );

        return after;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RentalPackagePolicyResponse inactivePackagePolicy(ActorPrincipal actor, UUID policyId) {
        requirePolicyOwner(actor);

        RentalPackagePolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental package policy not found"));

        facilityScopeService.assertCanManage(actor, policy.getFacility().getId());

        RentalPackagePolicyResponse before = toResponse(policy);

        policy.setPublicationStatus(PublicationStatus.INACTIVE);
        policy.setActive(false);

        RentalPackagePolicy saved = policyRepository.save(policy);
        RentalPackagePolicyResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "RENTAL_PACKAGE_POLICY_INACTIVATED",
            "RentalPackagePolicy",
            saved.getId(),
            saved.getFacility().getId(),
            before,
            after
        );

        return after;
    }

    @Transactional
    public void deletePackagePolicy(ActorPrincipal actor, UUID policyId) {
        requirePolicyOwner(actor);

        RentalPackagePolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental package policy not found"));

        facilityScopeService.assertCanManage(actor, policy.getFacility().getId());

        policy.setPublicationStatus(PublicationStatus.INACTIVE);
        policy.setActive(false);
        policyRepository.save(policy);

        auditLogService.recordMutation(
            "RENTAL_PACKAGE_POLICY_DEACTIVATED",
            "RentalPackagePolicy",
            policy.getId(),
            policy.getFacility().getId(),
            null,
            toResponse(policy)
        );
    }

    private void saveSetting(String key, String rawValue) {
        Optional<SystemSetting> opt = systemSettingRepository.findBySettingKey(key);
        SystemSetting setting;
        if (opt.isPresent()) {
            setting = opt.get();
        } else {
            setting = new SystemSetting();
            setting.setSettingKey(key);
            setting.setGroupName("Billing & Invoicing Rules");
            setting.setLabel(key);
            setting.setSettingType("text");
        }
        setting.setValue(rawValue);
        systemSettingRepository.save(setting);
    }

    private void validatePackagePeriod(RentalPackagePolicyRequest request, UUID facilityId, UUID excludedPolicyId) {
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw ApiExceptions.validation("Ngày kết thúc hiệu lực không được trước ngày bắt đầu.", null);
        }
        if (Boolean.FALSE.equals(request.active())) return;

        int months = request.resolveRentalMonths();
        var conflictingPolicy = policyRepository.findByFacility_IdAndRentalMonths(facilityId, months)
            .stream()
            .filter(existing -> existing.isActive())
            .filter(existing -> excludedPolicyId == null || !excludedPolicyId.equals(existing.getId()))
            .filter(existing -> periodsOverlap(
                request.effectiveFrom(), request.effectiveTo(),
                existing.getEffectiveFrom(), existing.getEffectiveTo()
            )).findFirst();
        if (conflictingPolicy.isPresent()) {
            var existing = conflictingPolicy.get();
            var facility = existing.getFacility();
            throw ApiExceptions.conflict(
                "Cơ sở " + facility.getName() + " (mã " + facility.getCode() + ") đã có gói "
                    + months + " tháng giảm "
                    + existing.getDiscountRate().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                    + "% đang áp dụng trong thời gian hiệu lực này. Không thể áp dụng thêm gói cùng kỳ hạn; vui lòng bỏ chọn cơ sở này hoặc điều chỉnh thời gian hiệu lực."
            );
        }
    }

    private boolean periodsOverlap(LocalDate firstStart, LocalDate firstEnd, LocalDate secondStart, LocalDate secondEnd) {
        boolean firstStartsBeforeSecondEnds = secondEnd == null || !firstStart.isAfter(secondEnd);
        boolean secondStartsBeforeFirstEnds = firstEnd == null || !secondStart.isAfter(firstEnd);
        return firstStartsBeforeSecondEnds && secondStartsBeforeFirstEnds;
    }

    private int getIntSetting(String key, int defaultValue) {
        return systemSettingRepository.findBySettingKey(key)
            .map(s -> {
                try {
                    String clean = s.getValue().replace("\"", "").trim();
                    return Integer.parseInt(clean);
                } catch (Exception e) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }

    private double getDoubleSetting(String key, double defaultValue) {
        return systemSettingRepository.findBySettingKey(key)
            .map(s -> {
                try {
                    String clean = s.getValue().replace("\"", "").trim();
                    return Double.parseDouble(clean);
                } catch (Exception e) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }

    private BigDecimal getBigDecimalSetting(String key, BigDecimal defaultValue) {
        return systemSettingRepository.findBySettingKey(key)
            .map(s -> {
                try {
                    String clean = s.getValue().replace("\"", "").trim();
                    return new BigDecimal(clean);
                } catch (Exception e) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }

    private boolean getBooleanSetting(String key, boolean defaultValue) {
        return systemSettingRepository.findBySettingKey(key)
            .map(s -> {
                String clean = s.getValue().replace("\"", "").trim();
                return Boolean.parseBoolean(clean);
            })
            .orElse(defaultValue);
    }

    private String getStringSetting(String key, String defaultValue) {
        return systemSettingRepository.findBySettingKey(key)
            .map(s -> s.getValue().replace("\"", "").trim())
            .orElse(defaultValue);
    }

    private void requirePolicyOwner(ActorPrincipal actor) {
        if (actor == null) throw ApiExceptions.unauthorized("Authentication is required");
        if (!actor.hasRole(RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Only Business Operations may edit rental policies");
        }
        authorizationService.require(actor, SystemPermission.MANAGE_POLICIES);
    }

    public RentalPackagePolicyResponse toResponse(RentalPackagePolicy p) {
        PublicationStatus pub = p.getPublicationStatus() != null
            ? p.getPublicationStatus()
            : (p.isActive() ? PublicationStatus.PUBLISHED : PublicationStatus.INACTIVE);

        String runtimeStatus;
        if (pub == PublicationStatus.DRAFT) {
            runtimeStatus = "DRAFT";
        } else if (pub == PublicationStatus.INACTIVE) {
            runtimeStatus = "INACTIVE";
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

        return new RentalPackagePolicyResponse(
            p.getId(),
            p.getFacility().getId(),
            p.getFacility().getName(),
            p.getCode(),
            p.getName(),
            p.getRentalMonths(),
            p.getRentalYears(),
            p.getDescription(),
            p.getDiscountRate(),
            p.getDepositRate(),
            p.getPolicyVersion(),
            pub,
            runtimeStatus,
            p.isActive(),
            p.getEffectiveFrom(),
            p.getEffectiveTo(),
            p.getCreatedAt(),
            p.getUpdatedAt()
        );
    }
}
