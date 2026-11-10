package com.storagehub.service;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RentalPackagePolicyProvisionerTests {

    @Mock
    private RentalPackagePolicyRepository repository;

    private Facility targetFacility;
    private Facility otherFacility;

    @BeforeEach
    void setUp() {
        targetFacility = new Facility();
        ReflectionTestUtils.setField(targetFacility, "id", UUID.randomUUID());
        targetFacility.setCode("KAG-F01");
        targetFacility.setName("Kho An Giang");

        otherFacility = new Facility();
        ReflectionTestUtils.setField(otherFacility, "id", UUID.randomUUID());
        otherFacility.setCode("KTD-F01");
        otherFacility.setName("Kho Thủ Đức");
    }

    @Test
    @DisplayName("Nếu cơ sở đã có gói thuê hiệu lực thì không tạo thêm")
    void provisionRentalPackages_whenFacilityAlreadyHasEffectivePackages_doesNothing() {
        RentalPackagePolicy existing = new RentalPackagePolicy();
        existing.setFacility(targetFacility);
        existing.setActive(true);
        existing.setPublicationStatus(PublicationStatus.PUBLISHED);
        existing.setEffectiveFrom(LocalDate.now().minusDays(10));
        existing.setEffectiveTo(null);

        when(repository.findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
            eq(targetFacility.getId()), any(LocalDate.class)
        )).thenReturn(List.of(existing));

        List<RentalPackagePolicy> result = RentalPackagePolicyProvisioner.provisionRentalPackages(repository, targetFacility);

        assertThat(result).containsExactly(existing);
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("Nếu hệ thống đã có gói active từ cơ sở khác, tự động nhân bản sang cơ sở mới kèm gói 1 tháng")
    void provisionRentalPackages_whenSystemHasActivePackages_clonesDistinctActivePackagesToNewFacility() {
        when(repository.findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
            eq(targetFacility.getId()), any(LocalDate.class)
        )).thenReturn(List.of());

        RentalPackagePolicy active3M = new RentalPackagePolicy();
        active3M.setFacility(otherFacility);
        active3M.setCode("PKG-3M-123456");
        active3M.setName("Gói thuê 3 tháng siêu ưu đãi");
        active3M.setRentalMonths(3);
        active3M.setDiscountRate(new BigDecimal("0.0800"));
        active3M.setPublicationStatus(PublicationStatus.PUBLISHED);
        active3M.setActive(true);
        active3M.setEffectiveFrom(LocalDate.now().minusDays(5));
        active3M.setEffectiveTo(null);

        when(repository.findAllByOrderByRentalMonthsAsc()).thenReturn(List.of(active3M));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<RentalPackagePolicy> result = RentalPackagePolicyProvisioner.provisionRentalPackages(repository, targetFacility);

        assertThat(result).hasSize(2); // Gói 3M được clone + Gói 1M cơ bản được bổ sung
        RentalPackagePolicy cloned3M = result.stream().filter(p -> p.getRentalMonths() == 3).findFirst().orElseThrow();
        assertThat(cloned3M.getFacility()).isEqualTo(targetFacility);
        assertThat(cloned3M.getCode()).isEqualTo("PKG-3M-123456");
        assertThat(cloned3M.getName()).isEqualTo("Gói thuê 3 tháng siêu ưu đãi");
        assertThat(cloned3M.getDiscountRate()).isEqualByComparingTo(new BigDecimal("0.0800"));
        assertThat(cloned3M.isActive()).isTrue();
        assertThat(cloned3M.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);

        RentalPackagePolicy added1M = result.stream().filter(p -> p.getRentalMonths() == 1).findFirst().orElseThrow();
        assertThat(added1M.getFacility()).isEqualTo(targetFacility);
        assertThat(added1M.getCode()).isEqualTo("PKG-1M");
        assertThat(added1M.isActive()).isTrue();
    }

    @Test
    @DisplayName("Nếu hệ thống chưa có gói nào, không tự ý sinh gói mặc định để tránh xung đột với cấu hình của BO")
    void provisionRentalPackages_whenSystemHasNoActivePackages_returnsEmptyList() {
        when(repository.findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
            eq(targetFacility.getId()), any(LocalDate.class)
        )).thenReturn(List.of());

        when(repository.findAllByOrderByRentalMonthsAsc()).thenReturn(List.of());

        List<RentalPackagePolicy> result = RentalPackagePolicyProvisioner.provisionRentalPackages(repository, targetFacility);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Tránh trùng lặp mã code nếu cơ sở đã tồn tại mã code tương tự")
    void provisionRentalPackages_handlesCodeCollisionsSafely() {
        when(repository.findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(
            eq(targetFacility.getId()), any(LocalDate.class)
        )).thenReturn(List.of());

        RentalPackagePolicy active1M = new RentalPackagePolicy();
        active1M.setFacility(otherFacility);
        active1M.setCode("PKG-1M");
        active1M.setName("Gói thuê 1 tháng");
        active1M.setRentalMonths(1);
        active1M.setDiscountRate(BigDecimal.ZERO);
        active1M.setPublicationStatus(PublicationStatus.PUBLISHED);
        active1M.setActive(true);
        active1M.setEffectiveFrom(LocalDate.now().minusDays(5));

        when(repository.findAllByOrderByRentalMonthsAsc()).thenReturn(List.of(active1M));
        when(repository.findByFacility_IdAndCode(targetFacility.getId(), "PKG-1M"))
            .thenReturn(Optional.of(new RentalPackagePolicy())); // Trùng mã
        when(repository.findByFacility_IdAndCode(targetFacility.getId(), "PKG-1M-1"))
            .thenReturn(Optional.empty());

        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<RentalPackagePolicy> result = RentalPackagePolicyProvisioner.provisionRentalPackages(repository, targetFacility);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCode()).isEqualTo("PKG-1M-1");
    }

    @Test
    @DisplayName("Trả về rỗng nếu facility truyền vào null hoặc không có id")
    void provisionRentalPackages_nullFacility_returnsEmpty() {
        List<RentalPackagePolicy> r1 = RentalPackagePolicyProvisioner.provisionRentalPackages(repository, null);
        List<RentalPackagePolicy> r2 = RentalPackagePolicyProvisioner.provisionRentalPackages(repository, new Facility());

        assertThat(r1).isEmpty();
        assertThat(r2).isEmpty();
        verifyNoInteractions(repository);
    }
}
