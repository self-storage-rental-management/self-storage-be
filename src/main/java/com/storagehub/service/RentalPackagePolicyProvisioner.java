package com.storagehub.service;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class RentalPackagePolicyProvisioner {

    private RentalPackagePolicyProvisioner() {}

    /**
     * Tự động khởi tạo hoặc nhân bản các gói thuê còn hiệu lực cho cơ sở.
     * 1. Nếu cơ sở đã có gói thuê hiệu lực -> bỏ qua.
     * 2. Nếu các cơ sở khác đã có gói thuê PUBLISHED & ACTIVE -> nhân bản (clone) sang cơ sở mới.
     * 3. Đảm bảo luôn có ít nhất gói cơ bản 1 tháng nếu chưa có.
     * 4. Nếu toàn hệ thống chưa có gói nào -> sinh bộ gói mặc định (1, 3, 6, 12 tháng).
     */
    public static List<RentalPackagePolicy> provisionRentalPackages(
        RentalPackagePolicyRepository repository,
        Facility facility
    ) {
        if (facility == null || facility.getId() == null) {
            return List.of();
        }

        LocalDate now = LocalDate.now();

        // 1. Kiểm tra xem cơ sở hiện tại đã có gói thuê active & còn hiệu lực chưa
        List<RentalPackagePolicy> existing = repository
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(facility.getId(), now)
            .stream()
            .filter(p -> p.getEffectiveTo() == null || !p.getEffectiveTo().isBefore(now))
            .toList();

        if (!existing.isEmpty()) {
            return existing;
        }

        // 2. Tìm các gói đang PUBLISHED & ACTIVE trong hệ thống (từ các cơ sở khác)
        List<RentalPackagePolicy> activeSystemPolicies = repository.findAllByOrderByRentalMonthsAsc().stream()
            .filter(RentalPackagePolicy::isActive)
            .filter(p -> p.getPublicationStatus() == PublicationStatus.PUBLISHED)
            .filter(p -> !p.getEffectiveFrom().isAfter(now) && (p.getEffectiveTo() == null || !p.getEffectiveTo().isBefore(now)))
            .filter(p -> !p.getFacility().getId().equals(facility.getId()))
            .toList();

        Set<String> usedCodes = new HashSet<>();
        List<RentalPackagePolicy> toSave = new ArrayList<>();

        if (!activeSystemPolicies.isEmpty()) {
            // Nhóm để lấy các gói đại diện theo (rentalMonths, discountRate)
            Map<String, RentalPackagePolicy> uniquePolicies = new LinkedHashMap<>();
            for (RentalPackagePolicy p : activeSystemPolicies) {
                String key = p.getRentalMonths() + "_" + (p.getDiscountRate() != null ? p.getDiscountRate().stripTrailingZeros().toPlainString() : "0");
                uniquePolicies.putIfAbsent(key, p);
            }

            for (RentalPackagePolicy sample : uniquePolicies.values()) {
                RentalPackagePolicy cloned = clonePolicy(facility, sample, now, usedCodes, repository);
                toSave.add(cloned);
            }

            // Đảm bảo luôn có tối thiểu gói 1 tháng nếu chưa có gói 1 tháng nào
            boolean hasOneMonth = toSave.stream().anyMatch(p -> p.getRentalMonths() == 1);
            if (!hasOneMonth) {
                toSave.add(createPackage(facility, "PKG-1M", "Gói thuê 1 tháng", 1, BigDecimal.ZERO, now, usedCodes, repository));
            }

            return repository.saveAll(toSave);
        }

        // 3. Nếu toàn hệ thống chưa có gói active nào (ví dụ BO đã xóa toàn bộ gói để tự cấu hình lại từ đầu):
        // Không tự ý sinh gói mặc định để tránh ghi đè hoặc gây xung đột với gói thuê mới mà BO muốn tạo.
        return List.of();
    }

    private static RentalPackagePolicy clonePolicy(
        Facility facility,
        RentalPackagePolicy sample,
        LocalDate now,
        Set<String> usedCodes,
        RentalPackagePolicyRepository repository
    ) {
        RentalPackagePolicy cloned = new RentalPackagePolicy();
        cloned.setFacility(facility);

        String baseCode = sample.getCode() != null && !sample.getCode().isBlank()
            ? sample.getCode().trim()
            : ("PKG-" + sample.getRentalMonths() + "M");
        String finalCode = resolveUniqueCode(facility.getId(), baseCode, usedCodes, repository);
        usedCodes.add(finalCode);
        cloned.setCode(finalCode);

        cloned.setName(sample.getName());
        cloned.setRentalMonths(sample.getRentalMonths());
        cloned.setRentalYears(sample.getRentalYears());
        cloned.setFreeSetupDays(sample.getFreeSetupDays());
        cloned.setDiscountRate(sample.getDiscountRate() != null ? sample.getDiscountRate() : BigDecimal.ZERO);
        cloned.setDepositRate(sample.getDepositRate() != null ? sample.getDepositRate() : new BigDecimal("0.2000"));
        cloned.setEarlyTerminationFeeCapMonths(sample.getEarlyTerminationFeeCapMonths() > 0
            ? sample.getEarlyTerminationFeeCapMonths() : EarlyTerminationPolicy.DEFAULT_FEE_CAP_MONTHS);
        cloned.setEarlyTerminationFeeCoefficient(sample.getEarlyTerminationFeeCoefficient() != null
            ? sample.getEarlyTerminationFeeCoefficient() : EarlyTerminationPolicy.DEFAULT_FEE_COEFFICIENT);
        cloned.setEarlyTerminationNoticeDays(sample.getEarlyTerminationNoticeDays() > 0
            ? sample.getEarlyTerminationNoticeDays() : EarlyTerminationPolicy.DEFAULT_NOTICE_DAYS);
        cloned.setDescription(sample.getDescription());
        cloned.setPolicyVersion(sample.getPolicyVersion() != null ? sample.getPolicyVersion() : "v1.0");
        cloned.setPublicationStatus(PublicationStatus.PUBLISHED);
        cloned.setActive(true);
        cloned.setEffectiveFrom(sample.getEffectiveFrom() != null ? sample.getEffectiveFrom() : now);
        cloned.setEffectiveTo(sample.getEffectiveTo());
        return cloned;
    }

    private static RentalPackagePolicy createPackage(
        Facility facility,
        String baseCode,
        String name,
        int months,
        BigDecimal discountRate,
        LocalDate effectiveFrom,
        Set<String> usedCodes,
        RentalPackagePolicyRepository repository
    ) {
        RentalPackagePolicy p = new RentalPackagePolicy();
        p.setFacility(facility);
        String finalCode = resolveUniqueCode(facility.getId(), baseCode, usedCodes, repository);
        usedCodes.add(finalCode);
        p.setCode(finalCode);
        p.setName(name);
        p.setRentalMonths(months);
        p.setFreeSetupDays(ReservationQuoteService.defaultFreeSetupDays(months));
        p.setDiscountRate(discountRate);
        p.setDepositRate(new BigDecimal("0.2000"));
        p.setEarlyTerminationFeeCapMonths(EarlyTerminationPolicy.DEFAULT_FEE_CAP_MONTHS);
        p.setEarlyTerminationFeeCoefficient(EarlyTerminationPolicy.DEFAULT_FEE_COEFFICIENT);
        p.setEarlyTerminationNoticeDays(EarlyTerminationPolicy.DEFAULT_NOTICE_DAYS);
        p.setPolicyVersion("v1.0");
        p.setPublicationStatus(PublicationStatus.PUBLISHED);
        p.setActive(true);
        p.setEffectiveFrom(effectiveFrom);
        return p;
    }

    private static String resolveUniqueCode(
        UUID facilityId,
        String baseCode,
        Set<String> usedCodes,
        RentalPackagePolicyRepository repository
    ) {
        String code = baseCode;
        int counter = 1;
        while (usedCodes.contains(code) || repository.findByFacility_IdAndCode(facilityId, code).isPresent()) {
            code = baseCode + "-" + counter++;
        }
        return code;
    }
}
