package com.storagehub.service;

import com.storagehub.api.rental.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RentalQueryService {
    private final RentalRepository repository;

    public PageResponse<RentalSummaryResponse> list(ActorPrincipal actor, RentalQuery query, boolean manager, String correlationId) {
        Set<UUID> facilities = authorize(actor, manager, query.facilityId());
        var page = repository.findAll(scoped(actor, manager, facilities).and(filters(query, manager)), query.pageable());
        return PageResponse.from(page.map(this::summary), correlationId);
    }

    public RentalDetailResponse detail(ActorPrincipal actor, UUID id, boolean manager) {
        Set<UUID> facilities = authorize(actor, manager, null);
        Rental r = repository.findOne(scoped(actor, manager, facilities).and((root, q, cb) -> cb.equal(root.get("id"), id)))
            .orElseThrow(() -> ApiExceptions.notFound("Rental was not found"));
        var s = summary(r);
        return new RentalDetailResponse(s.id(), s.customer(), s.facility(), s.storageUnit(), s.unitType(), s.status(),
            s.startDate(), s.contractEndDate(), s.monthlyPrice(), s.currency(), s.dataWarnings(), r.getReservation().getId(),
            r.getActualReturnedAt(), r.getCompletedAt(),
            new RentalDetailResponse.Financial("UNKNOWN", "VND", null, null, null,
                "Nguồn nghĩa vụ thanh toán và phân bổ chưa được tích hợp trong D1"),
            new RentalDetailResponse.Access("UNKNOWN", null, "Nguồn trạng thái truy cập chưa được tích hợp trong D1"));
    }

    private Set<UUID> authorize(ActorPrincipal actor, boolean manager, UUID requested) {
        if (actor == null) throw ApiExceptions.unauthorized("Authentication is required");
        if (!manager) {
            // Existing customer reservation endpoints use authenticated ownership, not a seeded permission grant.
            if (!actor.hasRole(RoleCode.CUSTOMER)) throw ApiExceptions.forbidden("Customer role is required");
            return Set.of();
        }
        if (!actor.hasRole(RoleCode.MANAGER) || !actor.hasPermission(SystemPermission.VIEW_RENTALS))
            throw ApiExceptions.forbidden("Manager VIEW_RENTALS permission is required");
        Set<UUID> readable = new HashSet<>();
        actor.facilityScopes().forEach((id, level) -> { if (level != null && level.includes(FacilityScopeLevel.READ)) readable.add(id); });
        if (readable.isEmpty() || requested != null && !readable.contains(requested))
            throw ApiExceptions.forbidden("Facility READ scope is required");
        return requested == null ? readable : Set.of(requested);
    }

    private Specification<Rental> scoped(ActorPrincipal actor, boolean manager, Set<UUID> facilities) {
        return (root, query, cb) -> {
            // Only to-one LEFT fetches: preserve damaged relations for integrity checks, avoid N+1 and row multiplication.
            if (query.getResultType() == Rental.class) {
                root.fetch("customer", JoinType.LEFT);
                root.fetch("facility", JoinType.LEFT);
                var unit = root.fetch("storageUnit", JoinType.LEFT);
                unit.fetch("facility", JoinType.LEFT);
                unit.fetch("unitType", JoinType.LEFT).fetch("facility", JoinType.LEFT);
                var reservation = root.fetch("reservation", JoinType.LEFT);
                reservation.fetch("facility", JoinType.LEFT);
                reservation.fetch("customer", JoinType.LEFT);
            }
            return manager ? root.get("facility").get("id").in(facilities)
                : cb.equal(root.get("customer").get("id"), actor.userId());
        };
    }

    private Specification<Rental> filters(RentalQuery filter, boolean manager) {
        return (root, q, cb) -> {
            List<Predicate> terms = new ArrayList<>();
            if (filter.status() != null) terms.add(cb.equal(root.get("status"), filter.status()));
            if (filter.endFrom() != null) terms.add(cb.greaterThanOrEqualTo(root.get("contractEndDate"), filter.endFrom()));
            if (filter.endTo() != null) terms.add(cb.lessThanOrEqualTo(root.get("contractEndDate"), filter.endTo()));
            if (!filter.search().isEmpty()) {
                String pattern = "%" + filter.search().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                List<Predicate> matches = new ArrayList<>();
                matches.add(cb.like(cb.lower(root.join("storageUnit", JoinType.LEFT).get("code")), pattern, '\\'));
                if (manager) matches.add(cb.like(cb.lower(root.join("customer", JoinType.LEFT).get("fullName")), pattern, '\\'));
                try { matches.add(cb.equal(root.get("id"), UUID.fromString(filter.search()))); }
                catch (IllegalArgumentException ignored) { /* A non-UUID still searches visible text fields. */ }
                terms.add(cb.or(matches.toArray(Predicate[]::new)));
            }
            return cb.and(terms.toArray(Predicate[]::new));
        };
    }

    private RentalSummaryResponse summary(Rental r) {
        integrity(r);
        var unit = r.getStorageUnit(); var type = unit.getUnitType();
        // No record-level activation provenance currently exists: do not pretend legacy date semantics were verified.
        var warnings = List.of(new RentalSummaryResponse.Warning("contractEndDate",
            "Giá trị ngày được lưu; nguồn xác minh semantics ngày của hồ sơ chưa được tích hợp trong D1"));
        return new RentalSummaryResponse(r.getId(), new RentalSummaryResponse.Customer(r.getCustomer().getId(), r.getCustomer().getFullName()),
            new RentalSummaryResponse.Facility(r.getFacility().getId(), r.getFacility().getCode(), r.getFacility().getName()),
            new RentalSummaryResponse.Unit(unit.getId(), unit.getCode()),
            new RentalSummaryResponse.UnitType(type.getId(), type.getCode(), type.getName()),
            r.getStatus(), r.getStartDate(), r.getContractEndDate(), r.getMonthlyPrice(), "VND", warnings);
    }
    private void integrity(Rental r) {
        boolean valid = r.getCustomer() != null && r.getFacility() != null && r.getStorageUnit() != null
            && r.getReservation() != null && r.getStorageUnit().getUnitType() != null
            && r.getStartDate() != null && r.getContractEndDate() != null && !r.getStartDate().isAfter(r.getContractEndDate())
            && r.getStatus() != null && r.getMonthlyPrice() != null && r.getMonthlyPrice().signum() >= 0;
        if (valid) {
            UUID facility = r.getFacility().getId();
            valid = same(facility, r.getStorageUnit().getFacility()) && same(facility, r.getStorageUnit().getUnitType().getFacility())
                && same(facility, r.getReservation().getFacility()) && same(r.getCustomer().getId(), r.getReservation().getCustomer());
        }
        if (!valid) {
            log.warn("Rental integrity failure rentalId={} correlationId={}", r.getId(), CorrelationIdContext.current());
            throw ApiExceptions.conflict("Rental core data is inconsistent; no records were changed");
        }
    }
    private boolean same(UUID id, BaseEntity entity) { return id != null && entity != null && id.equals(entity.getId()); }
}
