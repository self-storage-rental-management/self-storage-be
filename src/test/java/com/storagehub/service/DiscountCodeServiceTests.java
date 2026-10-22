package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.configuration.DiscountCodeRequest;
import com.storagehub.api.configuration.DiscountCodeResponse;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.DiscountCode;
import com.storagehub.domain.model.DiscountScope;
import com.storagehub.domain.model.DiscountType;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.PublicationStatus;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.DiscountCodeRepository;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class DiscountCodeServiceTests {

    @Mock DiscountCodeRepository discountCodeRepository;
    @Mock FacilityRepository facilityRepository;
    @Mock RentalPackagePolicyRepository policyRepository;
    @Mock UserRepository userRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock FacilityScopeService facilityScopeService;
    @Mock AuditLogService auditLogService;

    private DiscountCodeService service;
    private ActorPrincipal globalBusinessActor;
    private ActorPrincipal facilityScopedBusinessActor;
    private Facility facility;
    private UUID facilityId;
    private User businessUser;

    @BeforeEach
    void setUp() {
        service = new DiscountCodeService(
            discountCodeRepository,
            facilityRepository,
            policyRepository,
            userRepository,
            authorizationService,
            facilityScopeService,
            auditLogService
        );

        facilityId = UUID.randomUUID();
        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        facility.setName("Facility East");

        UUID userId = UUID.randomUUID();
        businessUser = new User();
        ReflectionTestUtils.setField(businessUser, "id", userId);
        businessUser.setFullName("Business Operator");

        globalBusinessActor = new ActorPrincipal(
            userId, UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of(SystemPermission.VIEW_POLICIES.code(), SystemPermission.MANAGE_POLICIES.code()),
            Map.of()
        );

        facilityScopedBusinessActor = new ActorPrincipal(
            userId, UUID.randomUUID(),
            Set.of(RoleCode.BUSINESS),
            Set.of(SystemPermission.VIEW_POLICIES.code(), SystemPermission.MANAGE_POLICIES.code()),
            Map.of(facilityId, FacilityScopeLevel.MANAGE)
        );
    }

    @Test
    void rejectsGlobalDiscountCreationWhenBusinessUserIsFacilityScoped() {
        when(facilityScopeService.hasGlobalScope(facilityScopedBusinessActor)).thenReturn(false);

        DiscountCodeRequest req = new DiscountCodeRequest(
            "SUMMER2026", "Summer Promo", "Global voucher",
            DiscountScope.GLOBAL, null, null,
            DiscountType.PERCENTAGE, new BigDecimal("10.00"),
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            PublicationStatus.DRAFT, 100
        );

        assertThatThrownBy(() -> service.createDiscount(facilityScopedBusinessActor, req))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Chỉ quản trị viên hoặc Business toàn hệ thống mới có quyền cấu hình voucher GLOBAL");
    }

    @Test
    void allowsGlobalDiscountCreationWhenUserHasGlobalScope() {
        when(facilityScopeService.hasGlobalScope(globalBusinessActor)).thenReturn(true);
        when(userRepository.findById(globalBusinessActor.userId())).thenReturn(Optional.of(businessUser));
        when(discountCodeRepository.save(any(DiscountCode.class))).thenAnswer(i -> {
            DiscountCode d = i.getArgument(0);
            ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            return d;
        });

        DiscountCodeRequest req = new DiscountCodeRequest(
            "GLOBAL10", "Global Promo", "Desc",
            DiscountScope.GLOBAL, null, null,
            DiscountType.PERCENTAGE, new BigDecimal("10.00"),
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            PublicationStatus.DRAFT, 100
        );

        DiscountCodeResponse response = service.createDiscount(globalBusinessActor, req);
        assertThat(response.code()).isEqualTo("GLOBAL10");
        assertThat(response.scope()).isEqualTo(DiscountScope.GLOBAL);
        assertThat(response.publicationStatus()).isEqualTo(PublicationStatus.DRAFT);
    }

    @Test
    void rejectsDirectEditWhenDiscountIsPublished() {
        UUID discountId = UUID.randomUUID();
        DiscountCode discount = new DiscountCode();
        ReflectionTestUtils.setField(discount, "id", discountId);
        discount.setCode("VOUCHER10");
        discount.setName("Active Voucher");
        discount.setScope(DiscountScope.GLOBAL);
        discount.setPublicationStatus(PublicationStatus.PUBLISHED);

        when(discountCodeRepository.findById(discountId)).thenReturn(Optional.of(discount));

        DiscountCodeRequest req = new DiscountCodeRequest(
            "VOUCHER10", "Edited Name", "Desc",
            DiscountScope.GLOBAL, null, null,
            DiscountType.PERCENTAGE, new BigDecimal("10.00"),
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            PublicationStatus.PUBLISHED, 100
        );

        assertThatThrownBy(() -> service.updateDiscount(globalBusinessActor, discountId, req))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Cấu hình voucher đã xuất bản không được chỉnh sửa trực tiếp");
    }

    @Test
    void calculatesCascadeDiscountCorrectlyOnRemainingRentalAfterDurationDiscount() {
        DiscountCode discount = new DiscountCode();
        discount.setCode("EXTRA10");
        discount.setScope(DiscountScope.GLOBAL);
        discount.setDiscountType(DiscountType.PERCENTAGE);
        discount.setDiscountValue(new BigDecimal("10.00")); // 10%
        discount.setPublicationStatus(PublicationStatus.PUBLISHED);
        discount.setEffectiveFrom(LocalDate.now().minusDays(1));
        discount.setEffectiveTo(LocalDate.now().plusDays(30));
        discount.setMaxUses(50);
        discount.setCurrentUses(5);

        when(discountCodeRepository.findByCodeIgnoreCase("EXTRA10")).thenReturn(Optional.of(discount));

        // Example: Base rent after 10% duration discount is 9,000,000 VND
        BigDecimal eligibleBase = new BigDecimal("9000000.00");
        var result = service.calculateVoucherDiscount("EXTRA10", facilityId, null, eligibleBase);

        assertThat(result).isNotNull();
        // Voucher 10% of 9,000,000 = 900,000 VND
        assertThat(result.discountAmount()).isEqualByComparingTo("900000.00");
    }

    @Test
    void atomicRedeemVoucherThrowsConflictWhenLastSlotUsedConcurrently() {
        UUID discountId = UUID.randomUUID();
        // Database atomic update returns 0 when max uses exceeded
        when(discountCodeRepository.incrementUsageIfAvailable(discountId)).thenReturn(0);

        assertThatThrownBy(() -> service.redeemVoucher(discountId))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Mã giảm giá đã hết lượt sử dụng");
    }
}
