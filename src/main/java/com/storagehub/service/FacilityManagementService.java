package com.storagehub.service;

import com.storagehub.api.facility.CreateFacilityRequest;
import com.storagehub.api.facility.FacilityResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FacilityManagementService {
    private static final Pattern NUMBERED_FACILITY_CODE = Pattern.compile("(.+-F)([0-9]{2,})");
    private final JdbcTemplate jdbc;

    private final FacilityRepository facilityRepository;
    private final RentalPackagePolicyRepository rentalPackagePolicyRepository;
    private final UserFacilityScopeRepository userFacilityScopeRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FacilityResponse createFacility(ActorPrincipal actor, CreateFacilityRequest request) {
        if (!actor.hasAnyRole(RoleCode.ADMIN, RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Chỉ Business Owner hoặc Admin mới có quyền tạo hoặc cập nhật cơ sở");
        }

        String code = availableFacilityCode(request.code(), Boolean.TRUE.equals(request.autoCode()));

        Facility facility = new Facility();
        facility.setCode(code);

        facility.setName(request.name().trim());
        facility.setAddress(request.address().trim());
        facility.setCity(request.city().trim());
        facility.setPhone(clean(request.phone()));
        facility.setAccessHours("24/7");
        facility.setImageUrl(clean(request.imageUrl()));
        facility.setClimateControlled(Boolean.TRUE.equals(request.climateControlled()));
        facility.setSecurityDescription(clean(request.securityDescription()));
        // A newly created facility is never customer-visible. A Manager must first
        // define the real unit types, then BO prices and publishes them.
        facility.setStatus(FacilityStatus.setup);

        Facility saved = facilityRepository.saveAndFlush(facility);
        RentalPackagePolicyProvisioner.provisionRentalPackages(rentalPackagePolicyRepository, saved);

        return new FacilityResponse(
            saved.getId(),
            saved.getCode(),
            saved.getName(),
            saved.getAddress(),
            saved.getCity(),
            saved.getPhone(),
            saved.getAccessHours(),
            saved.getImageUrl(),
            saved.isClimateControlled(),
            saved.getSecurityDescription(),
            managerNames(saved.getId()),
            saved.getStatus(),
            saved.getCreatedAt(),
            saved.getUpdatedAt()
        );
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String availableFacilityCode(String proposed, boolean automatic) {
        String code = proposed.trim().toUpperCase(Locale.ROOT);
        var parts = NUMBERED_FACILITY_CODE.matcher(code);
        if (parts.matches()) {
            String prefix = parts.group(1);
            // Manual and automatic creates share this prefix lock until their facility is committed.
            jdbc.update("INSERT INTO facility_code_prefixes(code_prefix) VALUES (?) ON DUPLICATE KEY UPDATE code_prefix=code_prefix", prefix);
            if (automatic) {
                BigInteger sequence = new BigInteger(parts.group(2));
                while (facilityRepository.findByCodeIgnoreCase(code).isPresent()) {
                    sequence = sequence.add(BigInteger.ONE);
                    code = prefix + String.format(Locale.ROOT, "%02d", sequence);
                    if (code.length() > 32) throw ApiExceptions.conflict("Đã hết mã cơ sở cho tiền tố này.");
                }
                return code;
            }
        } else if (automatic) {
            throw ApiExceptions.validation("Mã cơ sở tự động cần có tiền tố và số thứ tự, ví dụ HN-F01.", null);
        }
        if (facilityRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiExceptions.conflict("Facility code already exists");
        }
        return code;
    }

    private List<String> managerNames(UUID facilityId) {
        return userFacilityScopeRepository.findByFacility_Id(facilityId).stream()
            .filter(scope -> scope.getUser().getStatus() == com.storagehub.domain.model.UserStatus.ACTIVE)
            .filter(scope -> scope.getUser().getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.MANAGER))
            .map(scope -> scope.getUser().getFullName()).sorted().toList();
    }

}
