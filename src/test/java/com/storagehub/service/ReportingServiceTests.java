package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.reporting.BusinessPerformanceReportResponse;
import com.storagehub.api.reporting.BusinessRevenueReportResponse;
import com.storagehub.api.reporting.FacilityActivityResponse;
import com.storagehub.api.reporting.ManagerReportSummaryResponse;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.ActivityLog;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitType;
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
import java.time.Instant;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReportingServiceTests {

    @Mock FacilityRepository facilityRepository;
    @Mock StorageUnitRepository storageUnitRepository;
    @Mock RentalRepository rentalRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock CheckInRepository checkInRepository;
    @Mock ReturnCaseRepository returnCaseRepository;
    @Mock MaintenanceTaskRepository maintenanceTaskRepository;
    @Mock ActivityLogRepository activityLogRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock FacilityScopeService facilityScopeService;

    private ReportingService service;

    private ActorPrincipal managerActor;
    private ActorPrincipal businessActor;
    private Facility facility;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        service = new ReportingService(
            facilityRepository,
            storageUnitRepository,
            rentalRepository,
            paymentRepository,
            checkInRepository,
            returnCaseRepository,
            maintenanceTaskRepository,
            activityLogRepository,
            authorizationService,
            facilityScopeService
        );

        facilityId = UUID.randomUUID();
        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        facility.setName("Kho Quận 7");
        facility.setCity("Hồ Chí Minh");

        managerActor = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.MANAGER),
            Set.of("storage_units:read", "reports:read"),
            Map.of(facilityId, com.storagehub.domain.model.FacilityScopeLevel.MANAGE)
        );

        businessActor = new ActorPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of("reports:read", "storage_units:read"),
            Map.of(facilityId, com.storagehub.domain.model.FacilityScopeLevel.MANAGE)
        );
    }

    @Test
    void getManagerSummary_success() {
        UnitType standardType = new UnitType();
        standardType.setCode("STANDARD");
        standardType.setName("Standard Unit");

        StorageUnit u1 = new StorageUnit();
        u1.setStatus(StorageUnitStatus.occupied);
        u1.setUnitType(standardType);

        StorageUnit u2 = new StorageUnit();
        u2.setStatus(StorageUnitStatus.available);
        u2.setUnitType(standardType);

        Rental rental = new Rental();
        rental.setStatus(RentalStatus.active);
        rental.setMonthlyPrice(new BigDecimal("1500000"));
        rental.setContractEndDate(LocalDate.now().plusMonths(2));

        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.PAID);
        payment.setAmount(new BigDecimal("1500000"));

        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(storageUnitRepository.findByFacility_Id(facilityId)).thenReturn(List.of(u1, u2));
        when(rentalRepository.findByFacility_Id(facilityId)).thenReturn(List.of(rental));
        when(paymentRepository.findPaidPaymentsByFacility(facilityId)).thenReturn(List.of(payment));
        when(checkInRepository.countByReservation_Facility_IdAndStatusAndCheckedInAtBetween(eq(facilityId), eq(CheckInStatus.completed), any(), any()))
            .thenReturn(5L);
        when(returnCaseRepository.countByFacility_IdAndStatusNotIn(eq(facilityId), any()))
            .thenReturn(1L);
        when(maintenanceTaskRepository.countByFacility_IdAndStatusIn(eq(facilityId), any()))
            .thenReturn(2L);

        ManagerReportSummaryResponse summary = service.getManagerSummary(managerActor, facilityId);

        assertThat(summary).isNotNull();
        assertThat(summary.facilityId()).isEqualTo(facilityId);
        assertThat(summary.totalUnits()).isEqualTo(2);
        assertThat(summary.occupiedUnits()).isEqualTo(1);
        assertThat(summary.availableUnits()).isEqualTo(1);
        assertThat(summary.occupancyRate()).isEqualTo(50.0);
        assertThat(summary.monthlyRecurringRevenue()).isEqualByComparingTo("1500000");
        assertThat(summary.collectedRevenue()).isEqualByComparingTo("1500000");
        assertThat(summary.monthCheckinsCount()).isEqualTo(5);
        assertThat(summary.openReturnsCount()).isEqualTo(1);
        assertThat(summary.openMaintenanceTasksCount()).isEqualTo(2);

        verify(authorizationService).require(managerActor, SystemPermission.VIEW_UNITS);
        verify(facilityScopeService).assertCanRead(managerActor, facilityId);
    }

    @Test
    void getFacilityActivities_success() {
        ActivityLog log = new ActivityLog();
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        log.setAction("UNIT_STATUS_CHANGED");
        log.setEntityType("storage_unit");
        log.setEntityId(UUID.randomUUID());
        log.setFacility(facility);
        ReflectionTestUtils.setField(log, "createdAt", Instant.now());

        when(activityLogRepository.searchFacilityActivities(eq(facilityId), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(log)));

        PageResponse<FacilityActivityResponse> resp = service.getFacilityActivities(
            managerActor, facilityId, null, null, 0, 10, "corr-1"
        );

        assertThat(resp).isNotNull();
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).action()).isEqualTo("UNIT_STATUS_CHANGED");
    }

    @Test
    void getBusinessRevenueReport_success() {
        Payment p1 = new Payment();
        p1.setStatus(PaymentStatus.PAID);
        p1.setPurpose(PaymentType.MONTHLY_RENT);
        p1.setAmount(new BigDecimal("3000000"));
        p1.setPaidAt(Instant.now());

        when(paymentRepository.findByStatus(PaymentStatus.PAID)).thenReturn(List.of(p1));
        when(facilityRepository.findAll()).thenReturn(List.of(facility));

        BusinessRevenueReportResponse resp = service.getBusinessRevenueReport(businessActor, 6);

        assertThat(resp).isNotNull();
        assertThat(resp.totalRevenueCollected()).isEqualByComparingTo("3000000");
        assertThat(resp.monthlyBreakdown()).isNotEmpty();
        assertThat(resp.paymentPurposeBreakdown()).hasSize(1);
        verify(authorizationService).require(businessActor, SystemPermission.VIEW_REPORTS);
    }

    @Test
    void getBusinessPerformanceReport_success() {
        when(facilityRepository.findAll()).thenReturn(List.of(facility));
        when(storageUnitRepository.countByFacility_Id(facilityId)).thenReturn(10L);
        when(storageUnitRepository.countByFacility_IdAndStatus(facilityId, StorageUnitStatus.occupied)).thenReturn(8L);
        when(rentalRepository.countByFacility_IdAndStatus(facilityId, RentalStatus.active)).thenReturn(8L);
        when(returnCaseRepository.countByFacility_IdAndStatusNotIn(eq(facilityId), any())).thenReturn(1L);
        when(maintenanceTaskRepository.countByFacility_IdAndStatusIn(eq(facilityId), any())).thenReturn(2L);

        BusinessPerformanceReportResponse resp = service.getBusinessPerformanceReport(businessActor);

        assertThat(resp).isNotNull();
        assertThat(resp.totalFacilities()).isEqualTo(1);
        assertThat(resp.totalCapacityUnits()).isEqualTo(10);
        assertThat(resp.totalOccupiedUnits()).isEqualTo(8);
        assertThat(resp.systemOccupancyRate()).isEqualTo(80.0);
        assertThat(resp.facilityComparisons()).hasSize(1);
        assertThat(resp.facilityComparisons().get(0).occupancyRate()).isEqualTo(80.0);
    }
}
