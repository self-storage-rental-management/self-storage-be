package com.storagehub.service;

import com.storagehub.api.configuration.BusinessConfigResponse;
import com.storagehub.api.configuration.RentalPackagePolicyRequest;
import com.storagehub.api.configuration.RentalPackagePolicyResponse;
import com.storagehub.api.configuration.UpdateBusinessConfigRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.RentalPackagePolicy;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BusinessPolicyService {

    private final RentalPackagePolicyRepository policyRepository;
    private final FacilityRepository facilityRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

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
        authorizationService.require(actor, SystemPermission.MANAGE_POLICIES);

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

    @Transactional
    public RentalPackagePolicyResponse createPackagePolicy(ActorPrincipal actor, RentalPackagePolicyRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_POLICIES);

        Facility facility = facilityRepository.findById(request.facilityId())
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));

        facilityScopeService.assertCanManage(actor, facility.getId());

        if (policyRepository.findByFacility_IdAndCode(facility.getId(), request.code().trim()).isPresent()) {
            throw ApiExceptions.conflict("A package policy with code '" + request.code() + "' already exists for this facility");
        }

        RentalPackagePolicy policy = new RentalPackagePolicy();
        policy.setFacility(facility);
        policy.setCode(request.code().trim());
        policy.setName(request.name().trim());
        policy.setRentalMonths(request.rentalMonths());
        policy.setDiscountRate(request.discountRate());
        policy.setPolicyVersion(request.policyVersion().trim());
        policy.setActive(request.active() == null || request.active());
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

    @Transactional
    public RentalPackagePolicyResponse updatePackagePolicy(
        ActorPrincipal actor,
        UUID policyId,
        RentalPackagePolicyRequest request
    ) {
        authorizationService.require(actor, SystemPermission.MANAGE_POLICIES);

        RentalPackagePolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental package policy not found"));

        facilityScopeService.assertCanManage(actor, policy.getFacility().getId());

        RentalPackagePolicyResponse before = toResponse(policy);

        policy.setName(request.name().trim());
        policy.setRentalMonths(request.rentalMonths());
        policy.setDiscountRate(request.discountRate());
        policy.setPolicyVersion(request.policyVersion().trim());
        if (request.active() != null) {
            policy.setActive(request.active());
        }
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

    @Transactional
    public void deletePackagePolicy(ActorPrincipal actor, UUID policyId) {
        authorizationService.require(actor, SystemPermission.MANAGE_POLICIES);

        RentalPackagePolicy policy = policyRepository.findById(policyId)
            .orElseThrow(() -> ApiExceptions.notFound("Rental package policy not found"));

        facilityScopeService.assertCanManage(actor, policy.getFacility().getId());

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

    public RentalPackagePolicyResponse toResponse(RentalPackagePolicy p) {
        return new RentalPackagePolicyResponse(
            p.getId(),
            p.getFacility().getId(),
            p.getFacility().getName(),
            p.getCode(),
            p.getName(),
            p.getRentalMonths(),
            p.getDiscountRate(),
            p.getPolicyVersion(),
            p.isActive(),
            p.getEffectiveFrom(),
            p.getEffectiveTo(),
            p.getCreatedAt(),
            p.getUpdatedAt()
        );
    }
}
