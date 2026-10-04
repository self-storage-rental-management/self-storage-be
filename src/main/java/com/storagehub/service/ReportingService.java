package com.storagehub.service;

import com.storagehub.api.reporting.BusinessPerformanceReportResponse;
import com.storagehub.api.reporting.BusinessRevenueReportResponse;
import com.storagehub.api.reporting.FacilityActivityResponse;
import com.storagehub.api.reporting.ManagerReportSummaryResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.ActivityLog;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.ActivityLogRepository;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportingService {

    private final FacilityRepository facilityRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final RentalRepository rentalRepository;
    private final PaymentRepository paymentRepository;
    private final CheckInRepository checkInRepository;
    private final ReturnCaseRepository returnCaseRepository;
    private final MaintenanceTaskRepository maintenanceTaskRepository;
    private final ActivityLogRepository activityLogRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;

    @Transactional(readOnly = true)
    public ManagerReportSummaryResponse getManagerSummary(ActorPrincipal actor, UUID facilityId) {
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);

        UUID targetFacilityId = facilityId;
        if (targetFacilityId == null) {
            if (facilityScopeService.isFacilityScoped(actor)) {
                targetFacilityId = actor.facilityScopes().keySet().stream().findFirst()
                    .orElseThrow(() -> ApiExceptions.notFound("No facility accessible for actor"));
            } else {
                targetFacilityId = facilityRepository.findAll().stream().findFirst()
                    .map(Facility::getId)
                    .orElseThrow(() -> ApiExceptions.notFound("No facilities configured"));
            }
        } else {
            facilityScopeService.assertCanRead(actor, targetFacilityId);
        }

        Facility facility = facilityRepository.findById(targetFacilityId)
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));

        List<StorageUnit> units = storageUnitRepository.findByFacility_Id(targetFacilityId);
        long totalUnits = units.size();
        long availableUnits = units.stream().filter(u -> u.getStatus() == StorageUnitStatus.available).count();
        long occupiedUnits = units.stream().filter(u -> u.getStatus() == StorageUnitStatus.occupied).count();
        long maintenanceUnits = units.stream().filter(u -> u.getStatus() == StorageUnitStatus.maintenance).count();
        long reservedUnits = units.stream().filter(u -> u.getStatus() == StorageUnitStatus.reserved).count();

        double occupancyRate = totalUnits > 0
            ? BigDecimal.valueOf((occupiedUnits * 100.0) / totalUnits).setScale(1, RoundingMode.HALF_UP).doubleValue()
            : 0.0;

        List<ManagerReportSummaryResponse.StatusCount> statusBreakdown = Arrays.stream(StorageUnitStatus.values())
            .map(status -> {
                long count = units.stream().filter(u -> u.getStatus() == status).count();
                double pct = totalUnits > 0
                    ? BigDecimal.valueOf((count * 100.0) / totalUnits).setScale(1, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
                return new ManagerReportSummaryResponse.StatusCount(status.name(), getStatusLabel(status), count, pct);
            })
            .toList();

        Map<String, List<StorageUnit>> unitsByType = units.stream()
            .collect(Collectors.groupingBy(u -> u.getUnitType() != null ? u.getUnitType().getCode() : "standard"));

        List<ManagerReportSummaryResponse.TypeCapacity> typeBreakdown = unitsByType.entrySet().stream()
            .map(entry -> {
                String typeCode = entry.getKey();
                List<StorageUnit> typeUnits = entry.getValue();
                long totalTypeUnits = typeUnits.size();
                long occupiedTypeUnits = typeUnits.stream().filter(u -> u.getStatus() == StorageUnitStatus.occupied).count();
                double rate = totalTypeUnits > 0
                    ? BigDecimal.valueOf((occupiedTypeUnits * 100.0) / totalTypeUnits).setScale(1, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
                String typeName = typeUnits.getFirst().getUnitType() != null
                    ? typeUnits.getFirst().getUnitType().getName()
                    : typeCode;
                return new ManagerReportSummaryResponse.TypeCapacity(typeCode, typeName, totalTypeUnits, occupiedTypeUnits, rate);
            })
            .toList();

        List<Rental> facilityRentals = rentalRepository.findByFacility_Id(targetFacilityId);
        BigDecimal monthlyRecurringRevenue = facilityRentals.stream()
            .filter(r -> r.getStatus() == RentalStatus.active)
            .map(Rental::getMonthlyPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate today = LocalDate.now();
        long overdueRentalsCount = facilityRentals.stream()
            .filter(r -> r.getStatus() == RentalStatus.active
                && r.getContractEndDate() != null && r.getContractEndDate().isBefore(today))
            .count();

        List<Payment> paidPayments = paymentRepository.findPaidPaymentsByFacility(targetFacilityId);
        BigDecimal collectedRevenue = paidPayments.stream()
            .map(Payment::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        YearMonth currentYearMonth = YearMonth.now();
        Instant startOfMonth = currentYearMonth.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfMonth = currentYearMonth.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

        long monthCheckinsCount = checkInRepository.countByReservation_Facility_IdAndStatusAndCheckedInAtBetween(
            targetFacilityId, CheckInStatus.completed, startOfMonth, endOfMonth
        );

        long openReturnsCount = returnCaseRepository.countByFacility_IdAndStatusNotIn(
            targetFacilityId, List.of(ReturnCaseStatus.completed)
        );

        long openMaintenanceTasksCount = maintenanceTaskRepository.countByFacility_IdAndStatusIn(
            targetFacilityId, List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
        );

        return new ManagerReportSummaryResponse(
            facility.getId(),
            facility.getName(),
            totalUnits,
            availableUnits,
            occupiedUnits,
            maintenanceUnits,
            reservedUnits,
            occupancyRate,
            monthlyRecurringRevenue,
            collectedRevenue,
            overdueRentalsCount,
            monthCheckinsCount,
            openReturnsCount,
            openMaintenanceTasksCount,
            statusBreakdown,
            typeBreakdown
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<FacilityActivityResponse> getFacilityActivities(
        ActorPrincipal actor,
        UUID facilityId,
        String entityType,
        String search,
        int page,
        int size,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);

        UUID targetFacilityId = facilityId;
        if (targetFacilityId != null) {
            facilityScopeService.assertCanRead(actor, targetFacilityId);
        } else if (facilityScopeService.isFacilityScoped(actor)) {
            targetFacilityId = actor.facilityScopes().keySet().stream().findFirst().orElse(null);
        }

        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size between 1 and 100", null);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FacilityActivityResponse> results = activityLogRepository.searchFacilityActivities(
            targetFacilityId,
            search != null && !search.isBlank() ? search.trim() : null,
            entityType != null && !entityType.isBlank() ? entityType.trim() : null,
            pageable
        ).map(this::toActivityResponse);

        return PageResponse.from(results, correlationId);
    }

    @Transactional(readOnly = true)
    public BusinessRevenueReportResponse getBusinessRevenueReport(ActorPrincipal actor, Integer months) {
        authorizationService.require(actor, SystemPermission.VIEW_REPORTS);

        int monthsToLookBack = months != null && months > 0 && months <= 24 ? months : 6;
        List<Payment> allPaid = paymentRepository.findByStatus(PaymentStatus.PAID);

        BigDecimal totalCollected = allPaid.stream()
            .map(Payment::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM");
        YearMonth current = YearMonth.now();

        Map<String, List<Payment>> paymentsByMonth = new LinkedHashMap<>();
        for (int i = monthsToLookBack - 1; i >= 0; i--) {
            YearMonth targetMonth = current.minusMonths(i);
            paymentsByMonth.put(targetMonth.format(monthFormatter), new ArrayList<>());
        }

        for (Payment payment : allPaid) {
            Instant time = payment.getPaidAt() != null ? payment.getPaidAt() : payment.getCreatedAt();
            if (time != null) {
                String monthKey = time.atZone(ZoneOffset.UTC).format(monthFormatter);
                if (paymentsByMonth.containsKey(monthKey)) {
                    paymentsByMonth.get(monthKey).add(payment);
                }
            }
        }

        List<BusinessRevenueReportResponse.MonthlyRevenue> monthlyBreakdown = paymentsByMonth.entrySet().stream()
            .map(entry -> {
                String month = entry.getKey();
                List<Payment> monthPayments = entry.getValue();
                BigDecimal rev = monthPayments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                return new BusinessRevenueReportResponse.MonthlyRevenue(month, rev, monthPayments.size());
            })
            .toList();

        List<Facility> facilities = facilityRepository.findAll();
        List<BusinessRevenueReportResponse.FacilityRevenue> facilityBreakdown = facilities.stream()
            .map(fac -> {
                List<Payment> facPayments = allPaid.stream()
                    .filter(p -> p.getReservation() != null && p.getReservation().getFacility().getId().equals(fac.getId()))
                    .toList();
                BigDecimal facRevenue = facPayments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                long totalUnits = storageUnitRepository.countByFacility_Id(fac.getId());
                long occupiedUnits = storageUnitRepository.countByFacility_IdAndStatus(fac.getId(), StorageUnitStatus.occupied);
                double occRate = totalUnits > 0
                    ? BigDecimal.valueOf((occupiedUnits * 100.0) / totalUnits).setScale(1, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
                long activeRentals = rentalRepository.countByFacility_IdAndStatus(fac.getId(), RentalStatus.active);
                return new BusinessRevenueReportResponse.FacilityRevenue(fac.getId(), fac.getName(), facRevenue, occRate, activeRentals);
            })
            .sorted(Comparator.comparing(BusinessRevenueReportResponse.FacilityRevenue::revenue).reversed())
            .toList();

        Map<PaymentType, List<Payment>> byPurpose = allPaid.stream().collect(Collectors.groupingBy(Payment::getPurpose));
        List<BusinessRevenueReportResponse.PurposeRevenue> purposeBreakdown = byPurpose.entrySet().stream()
            .map(entry -> {
                String purpose = entry.getKey().name();
                BigDecimal sum = entry.getValue().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                return new BusinessRevenueReportResponse.PurposeRevenue(purpose, sum, entry.getValue().size());
            })
            .sorted(Comparator.comparing(BusinessRevenueReportResponse.PurposeRevenue::amount).reversed())
            .toList();

        return new BusinessRevenueReportResponse(
            totalCollected,
            BigDecimal.ZERO,
            totalCollected,
            monthlyBreakdown,
            facilityBreakdown,
            purposeBreakdown
        );
    }

    @Transactional(readOnly = true)
    public BusinessPerformanceReportResponse getBusinessPerformanceReport(ActorPrincipal actor) {
        authorizationService.require(actor, SystemPermission.VIEW_REPORTS);

        List<Facility> facilities = facilityRepository.findAll();
        long totalCapacity = 0;
        long totalOccupied = 0;

        List<BusinessPerformanceReportResponse.FacilityPerformance> comparisons = new ArrayList<>();
        for (Facility fac : facilities) {
            long units = storageUnitRepository.countByFacility_Id(fac.getId());
            long occupied = storageUnitRepository.countByFacility_IdAndStatus(fac.getId(), StorageUnitStatus.occupied);
            totalCapacity += units;
            totalOccupied += occupied;

            double occRate = units > 0
                ? BigDecimal.valueOf((occupied * 100.0) / units).setScale(1, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

            long activeRentals = rentalRepository.countByFacility_IdAndStatus(fac.getId(), RentalStatus.active);
            long openReturns = returnCaseRepository.countByFacility_IdAndStatusNotIn(
                fac.getId(), List.of(ReturnCaseStatus.completed)
            );
            long openMaintenance = maintenanceTaskRepository.countByFacility_IdAndStatusIn(
                fac.getId(), List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
            );

            comparisons.add(new BusinessPerformanceReportResponse.FacilityPerformance(
                fac.getId(),
                fac.getName(),
                fac.getCity(),
                units,
                occupied,
                occRate,
                activeRentals,
                openReturns,
                openMaintenance
            ));
        }

        double systemOccupancyRate = totalCapacity > 0
            ? BigDecimal.valueOf((totalOccupied * 100.0) / totalCapacity).setScale(1, RoundingMode.HALF_UP).doubleValue()
            : 0.0;

        return new BusinessPerformanceReportResponse(
            facilities.size(),
            totalCapacity,
            totalOccupied,
            systemOccupancyRate,
            comparisons
        );
    }

    private FacilityActivityResponse toActivityResponse(ActivityLog log) {
        return new FacilityActivityResponse(
            log.getId(),
            log.getAction(),
            humanizeAction(log.getAction()),
            log.getEntityType(),
            log.getEntityId(),
            log.getActor() != null ? log.getActor().getId() : null,
            log.getActor() != null ? log.getActor().getFullName() : "Hệ thống",
            log.getActor() != null ? log.getActor().getEmail() : "SYSTEM",
            log.getFacility() != null ? log.getFacility().getId() : null,
            log.getFacility() != null ? log.getFacility().getName() : null,
            null,
            log.getBeforeStateJson(),
            log.getAfterStateJson(),
            log.getCorrelationId(),
            log.getCreatedAt()
        );
    }

    private String getStatusLabel(StorageUnitStatus status) {
        return switch (status) {
            case available -> "Sẵn sàng";
            case reserved -> "Đã giữ chỗ";
            case occupied -> "Đang thuê";
            case maintenance -> "Bảo trì";
            case held -> "Tạm giữ";
            case assigned -> "Đã phân bổ";
        };
    }

    private String humanizeAction(String action) {
        if (action == null) return "Hoạt động";
        return action.replace("_", " ");
    }
}
