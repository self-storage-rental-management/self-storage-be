package com.storagehub.service;

import com.storagehub.api.configuration.DiscountCodeRequest;
import com.storagehub.api.configuration.DiscountCodeResponse;
import com.storagehub.common.api.ApiExceptions;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DiscountCodeService {

    private final DiscountCodeRepository discountCodeRepository;
    private final FacilityRepository facilityRepository;
    private final RentalPackagePolicyRepository policyRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<DiscountCodeResponse> listDiscounts(ActorPrincipal actor, UUID facilityId) {
        authorizationService.require(actor, SystemPermission.VIEW_POLICIES);

        List<DiscountCode> list;
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
            list = discountCodeRepository.findByFacility_IdOrderByCreatedAtDesc(facilityId);
        } else {
            list = discountCodeRepository.findAllByOrderByCreatedAtDesc();
            if (!facilityScopeService.hasGlobalScope(actor)) {
                list = list.stream()
                    .filter(d -> d.getScope() != DiscountScope.GLOBAL)
                    .filter(d -> d.getFacility() != null && facilityScopeService.canAccessFacility(actor, d.getFacility().getId(), FacilityScopeLevel.READ))
                    .toList();
            }
        }

        return list.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DiscountCodeResponse getDiscount(ActorPrincipal actor, UUID id) {
        authorizationService.require(actor, SystemPermission.VIEW_POLICIES);
        DiscountCode discount = discountCodeRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Mã giảm giá không tồn tại"));

        if (discount.getFacility() != null) {
            facilityScopeService.assertCanRead(actor, discount.getFacility().getId());
        } else if (discount.getScope() == DiscountScope.GLOBAL && !facilityScopeService.hasGlobalScope(actor)) {
            throw ApiExceptions.forbidden("Actor does not have global scope to view this discount");
        }

        return toResponse(discount);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public DiscountCodeResponse createDiscount(ActorPrincipal actor, DiscountCodeRequest request) {
        requireDiscountManager(actor);

        String cleanCode = request.code().trim().toUpperCase();
        if (discountCodeRepository.existsByCodeIgnoreCase(cleanCode)) {
            throw ApiExceptions.conflict("Mã giảm giá '" + cleanCode + "' đã tồn tại trong hệ thống");
        }

        validateScopeAndActor(actor, request.scope(), request.facilityId(), request.policyId());
        validateDiscountValues(request);

        Facility facility = null;
        if (request.facilityId() != null) {
            facility = facilityRepository.findById(request.facilityId())
                .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy cơ sở"));
        }

        RentalPackagePolicy policy = null;
        if (request.policyId() != null) {
            policy = policyRepository.findById(request.policyId())
                .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy chính sách gói thuê"));
            if (facility != null && !policy.getFacility().getId().equals(facility.getId())) {
                throw ApiExceptions.validation("Chính sách gói thuê không thuộc cơ sở đã chọn", null);
            }
        }

        User user = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("User not found"));

        PublicationStatus pubStatus = request.publicationStatus() != null ? request.publicationStatus() : PublicationStatus.DRAFT;

        DiscountCode discount = new DiscountCode();
        discount.setCode(cleanCode);
        discount.setName(request.name().trim());
        discount.setDescription(request.description());
        discount.setScope(request.scope());
        discount.setFacility(facility);
        discount.setPolicy(policy);
        discount.setDiscountType(request.discountType());
        discount.setDiscountValue(request.discountValue());
        discount.setEffectiveFrom(request.effectiveFrom());
        discount.setEffectiveTo(request.effectiveTo());
        discount.setMaxUses(request.maxUses());
        discount.setCurrentUses(0);
        discount.setPublicationStatus(pubStatus);
        discount.setCreatedBy(user);

        DiscountCode saved = discountCodeRepository.save(discount);

        auditLogService.recordMutation(
            "DISCOUNT_CODE_CREATED",
            "DiscountCode",
            saved.getId(),
            saved.getFacility() != null ? saved.getFacility().getId() : null,
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public DiscountCodeResponse updateDiscount(ActorPrincipal actor, UUID id, DiscountCodeRequest request) {
        requireDiscountManager(actor);

        DiscountCode discount = discountCodeRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Mã giảm giá không tồn tại"));

        if (discount.getPublicationStatus() == PublicationStatus.PUBLISHED) {
            throw ApiExceptions.validation("Cấu hình voucher đã xuất bản không được chỉnh sửa trực tiếp. Vui lòng chuyển trạng thái về INACTIVE trước khi chỉnh sửa.", null);
        }

        validateScopeAndActor(actor, request.scope(), request.facilityId(), request.policyId());
        validateDiscountValues(request);

        String cleanCode = request.code().trim().toUpperCase();
        if (!discount.getCode().equalsIgnoreCase(cleanCode) && discountCodeRepository.existsByCodeIgnoreCase(cleanCode)) {
            throw ApiExceptions.conflict("Mã giảm giá '" + cleanCode + "' đã tồn tại trong hệ thống");
        }

        Facility facility = null;
        if (request.facilityId() != null) {
            facility = facilityRepository.findById(request.facilityId())
                .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy cơ sở"));
        }

        RentalPackagePolicy policy = null;
        if (request.policyId() != null) {
            policy = policyRepository.findById(request.policyId())
                .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy chính sách gói thuê"));
        }

        DiscountCodeResponse before = toResponse(discount);

        discount.setCode(cleanCode);
        discount.setName(request.name().trim());
        discount.setDescription(request.description());
        discount.setScope(request.scope());
        discount.setFacility(facility);
        discount.setPolicy(policy);
        discount.setDiscountType(request.discountType());
        discount.setDiscountValue(request.discountValue());
        discount.setEffectiveFrom(request.effectiveFrom());
        discount.setEffectiveTo(request.effectiveTo());
        discount.setMaxUses(request.maxUses());
        if (request.publicationStatus() != null && request.publicationStatus() != PublicationStatus.PUBLISHED) {
            discount.setPublicationStatus(request.publicationStatus());
        }

        DiscountCode saved = discountCodeRepository.save(discount);
        DiscountCodeResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "DISCOUNT_CODE_UPDATED",
            "DiscountCode",
            saved.getId(),
            saved.getFacility() != null ? saved.getFacility().getId() : null,
            before,
            after
        );

        return after;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public DiscountCodeResponse publishDiscount(ActorPrincipal actor, UUID id) {
        requireDiscountManager(actor);

        DiscountCode discount = discountCodeRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Mã giảm giá không tồn tại"));

        validateScopeAndActor(actor, discount.getScope(),
            discount.getFacility() != null ? discount.getFacility().getId() : null,
            discount.getPolicy() != null ? discount.getPolicy().getId() : null);

        if (discount.getEffectiveTo() != null && discount.getEffectiveTo().isBefore(discount.getEffectiveFrom())) {
            throw ApiExceptions.validation("effectiveTo must be on or after effectiveFrom", null);
        }

        DiscountCodeResponse before = toResponse(discount);

        discount.setPublicationStatus(PublicationStatus.PUBLISHED);
        DiscountCode saved = discountCodeRepository.save(discount);
        DiscountCodeResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "DISCOUNT_CODE_PUBLISHED",
            "DiscountCode",
            saved.getId(),
            saved.getFacility() != null ? saved.getFacility().getId() : null,
            before,
            after
        );

        return after;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public DiscountCodeResponse inactiveDiscount(ActorPrincipal actor, UUID id) {
        requireDiscountManager(actor);

        DiscountCode discount = discountCodeRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Mã giảm giá không tồn tại"));

        validateScopeAndActor(actor, discount.getScope(),
            discount.getFacility() != null ? discount.getFacility().getId() : null,
            discount.getPolicy() != null ? discount.getPolicy().getId() : null);

        DiscountCodeResponse before = toResponse(discount);

        discount.setPublicationStatus(PublicationStatus.INACTIVE);
        DiscountCode saved = discountCodeRepository.save(discount);
        DiscountCodeResponse after = toResponse(saved);

        auditLogService.recordMutation(
            "DISCOUNT_CODE_INACTIVATED",
            "DiscountCode",
            saved.getId(),
            saved.getFacility() != null ? saved.getFacility().getId() : null,
            before,
            after
        );

        return after;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deleteDiscount(ActorPrincipal actor, UUID id) {
        requireDiscountManager(actor);

        DiscountCode discount = discountCodeRepository.findById(id)
            .orElseThrow(() -> ApiExceptions.notFound("Mã giảm giá không tồn tại"));

        validateScopeAndActor(actor, discount.getScope(),
            discount.getFacility() != null ? discount.getFacility().getId() : null,
            discount.getPolicy() != null ? discount.getPolicy().getId() : null);

        discount.setPublicationStatus(PublicationStatus.INACTIVE);
        discountCodeRepository.save(discount);

        auditLogService.recordMutation(
            "DISCOUNT_CODE_DEACTIVATED",
            "DiscountCode",
            discount.getId(),
            discount.getFacility() != null ? discount.getFacility().getId() : null,
            null,
            toResponse(discount)
        );
    }

    /**
     * Validates voucher and calculates discount amount on the baseEligibleAmount (post-duration discount).
     */
    @Transactional(readOnly = true)
    public VoucherApplicationResult calculateVoucherDiscount(
        String code,
        UUID facilityId,
        UUID policyId,
        BigDecimal baseEligibleAmount
    ) {
        if (code == null || code.isBlank()) {
            return null;
        }

        DiscountCode discount = discountCodeRepository.findByCodeIgnoreCase(code.trim())
            .orElseThrow(() -> ApiExceptions.validation("Mã giảm giá '" + code + "' không tồn tại", null));

        if (discount.getPublicationStatus() != PublicationStatus.PUBLISHED) {
            throw ApiExceptions.validation("Mã giảm giá '" + code + "' chưa được phát hành hoặc đã ngừng kích hoạt", null);
        }

        LocalDate now = LocalDate.now();
        if (discount.getEffectiveFrom().isAfter(now)) {
            throw ApiExceptions.validation("Mã giảm giá chưa đến ngày áp dụng (hiệu lực từ " + discount.getEffectiveFrom() + ")", null);
        }
        if (discount.getEffectiveTo() != null && discount.getEffectiveTo().isBefore(now)) {
            throw ApiExceptions.validation("Mã giảm giá đã hết hạn sử dụng (hạn chót " + discount.getEffectiveTo() + ")", null);
        }

        if (discount.getMaxUses() != null && discount.getCurrentUses() >= discount.getMaxUses()) {
            throw ApiExceptions.validation("Mã giảm giá đã đạt giới hạn số lượt sử dụng", null);
        }

        // Scope validation
        if (discount.getScope() == DiscountScope.FACILITY) {
            if (facilityId == null || discount.getFacility() == null || !facilityId.equals(discount.getFacility().getId())) {
                throw ApiExceptions.validation("Mã giảm giá chỉ áp dụng cho cơ sở " + (discount.getFacility() != null ? discount.getFacility().getName() : ""), null);
            }
        } else if (discount.getScope() == DiscountScope.POLICY) {
            if (policyId == null || discount.getPolicy() == null || !policyId.equals(discount.getPolicy().getId())) {
                throw ApiExceptions.validation("Mã giảm giá chỉ áp dụng cho gói thuê quy định", null);
            }
        }

        BigDecimal discountAmount;
        if (discount.getDiscountType() == DiscountType.PERCENTAGE) {
            BigDecimal rate = discount.getDiscountValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            discountAmount = baseEligibleAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        } else {
            discountAmount = discount.getDiscountValue().min(baseEligibleAmount).setScale(2, RoundingMode.HALF_UP);
        }

        return new VoucherApplicationResult(
            discount.getId(),
            discount.getCode(),
            discount.getDiscountType(),
            discount.getDiscountValue(),
            discountAmount
        );
    }

    /**
     * Atomically consumes one use of the voucher.
     */
    @Transactional
    public void redeemVoucher(UUID discountId) {
        if (discountId == null) return;
        int rowsUpdated = discountCodeRepository.incrementUsageIfAvailable(discountId);
        if (rowsUpdated == 0) {
            throw ApiExceptions.conflict("Mã giảm giá đã hết lượt sử dụng hoặc không còn hiệu lực do giao dịch đồng thời");
        }
    }

    private void validateScopeAndActor(ActorPrincipal actor, DiscountScope scope, UUID facilityId, UUID policyId) {
        if (scope == DiscountScope.GLOBAL) {
            if (!facilityScopeService.hasGlobalScope(actor)) {
                throw ApiExceptions.forbidden("Chỉ quản trị viên hoặc Business toàn hệ thống mới có quyền cấu hình voucher GLOBAL");
            }
        } else if (scope == DiscountScope.FACILITY) {
            if (facilityId == null) {
                throw ApiExceptions.validation("facilityId là bắt buộc đối với voucher phạm vi FACILITY", null);
            }
            facilityScopeService.assertCanManage(actor, facilityId);
        } else if (scope == DiscountScope.POLICY) {
            if (policyId == null) {
                throw ApiExceptions.validation("policyId là bắt buộc đối với voucher phạm vi POLICY", null);
            }
            RentalPackagePolicy p = policyRepository.findById(policyId)
                .orElseThrow(() -> ApiExceptions.notFound("Chính sách gói thuê không tồn tại"));
            facilityScopeService.assertCanManage(actor, p.getFacility().getId());
        }
    }

    private void validateDiscountValues(DiscountCodeRequest request) {
        if (request.discountType() == DiscountType.PERCENTAGE) {
            if (request.discountValue().compareTo(BigDecimal.ZERO) <= 0 || request.discountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiExceptions.validation("Mức giảm phần trăm phải nằm trong khoảng (0, 100]", null);
            }
        } else {
            if (request.discountValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiExceptions.validation("Số tiền giảm phải lớn hơn 0", null);
            }
        }

        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw ApiExceptions.validation("effectiveTo phải sau hoặc bằng effectiveFrom", null);
        }

        if (request.maxUses() != null && request.maxUses() < 1) {
            throw ApiExceptions.validation("maxUses phải >= 1 nếu được chỉ định", null);
        }
    }

    private void requireDiscountManager(ActorPrincipal actor) {
        if (actor == null) throw ApiExceptions.unauthorized("Authentication is required");
        if (!actor.hasRole(RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Chỉ ban điều hành (Business Operations) mới có quyền quản lý mã giảm giá");
        }
        authorizationService.require(actor, SystemPermission.MANAGE_POLICIES);
    }

    public DiscountCodeResponse toResponse(DiscountCode d) {
        PublicationStatus pub = d.getPublicationStatus() != null ? d.getPublicationStatus() : PublicationStatus.DRAFT;
        String runtimeStatus;
        if (pub == PublicationStatus.DRAFT) {
            runtimeStatus = "DRAFT";
        } else if (pub == PublicationStatus.INACTIVE) {
            runtimeStatus = "INACTIVE";
        } else {
            LocalDate now = LocalDate.now();
            if (d.getEffectiveFrom() != null && d.getEffectiveFrom().isAfter(now)) {
                runtimeStatus = "SCHEDULED";
            } else if (d.getEffectiveTo() != null && d.getEffectiveTo().isBefore(now)) {
                runtimeStatus = "EXPIRED";
            } else if (d.getMaxUses() != null && d.getCurrentUses() >= d.getMaxUses()) {
                runtimeStatus = "EXPIRED";
            } else {
                runtimeStatus = "ACTIVE";
            }
        }

        return new DiscountCodeResponse(
            d.getId(),
            d.getCode(),
            d.getName(),
            d.getDescription(),
            d.getScope(),
            d.getFacility() != null ? d.getFacility().getId() : null,
            d.getFacility() != null ? d.getFacility().getName() : null,
            d.getPolicy() != null ? d.getPolicy().getId() : null,
            d.getPolicy() != null ? d.getPolicy().getName() : null,
            d.getDiscountType(),
            d.getDiscountValue(),
            d.getEffectiveFrom(),
            d.getEffectiveTo(),
            pub,
            runtimeStatus,
            d.getMaxUses(),
            d.getCurrentUses(),
            d.getCreatedBy() != null ? d.getCreatedBy().getId() : null,
            d.getCreatedBy() != null ? d.getCreatedBy().getFullName() : null,
            d.getCreatedAt(),
            d.getUpdatedAt()
        );
    }

    public record VoucherApplicationResult(
        UUID discountId,
        String voucherCode,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal discountAmount
    ) {}
}
