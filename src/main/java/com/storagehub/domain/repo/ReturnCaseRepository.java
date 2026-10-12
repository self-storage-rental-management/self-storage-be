package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReturnCaseRepository extends JpaRepository<ReturnCase, UUID>, JpaSpecificationExecutor<ReturnCase> {

    Optional<ReturnCase> findByIdAndCustomer_Id(UUID id, UUID customerId);

    Page<ReturnCase> findByCustomer_Id(UUID customerId, Pageable pageable);

    Optional<ReturnCase> findByRental_Id(UUID rentalId);

    boolean existsByRental_IdAndStatusNotIn(UUID rentalId, Collection<ReturnCaseStatus> statuses);

    default Page<ReturnCase> searchReturnCases(
        UUID facilityId,
        ReturnCaseStatus status,
        Collection<UUID> allowedFacilityIds,
        String q,
        Pageable pageable
    ) {
        return findAll((root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (facilityId != null) predicates.add(cb.equal(root.get("facility").get("id"), facilityId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            // Never bind a collection to a scalar IS NULL expression. Empty scope denies all rows.
            if (allowedFacilityIds != null) predicates.add(allowedFacilityIds.isEmpty()
                ? cb.disjunction() : root.get("facility").get("id").in(allowedFacilityIds));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("customer").get("fullName")), pattern),
                    cb.like(cb.lower(root.get("customer").get("email")), pattern),
                    cb.like(cb.lower(root.get("customer").get("phone")), pattern),
                    cb.like(cb.lower(root.get("storageUnit").get("code")), pattern),
                    cb.like(cb.lower(root.get("facility").get("name")), pattern)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }

    long countByFacility_IdAndStatusNotIn(UUID facilityId, Collection<ReturnCaseStatus> statuses);

    long countByStatusNotIn(Collection<ReturnCaseStatus> statuses);

    List<ReturnCase> findByFacility_IdInAndStatusIn(
        Collection<UUID> facilityIds,
        Collection<ReturnCaseStatus> statuses
    );

    List<ReturnCase> findByStatusIn(Collection<ReturnCaseStatus> statuses);
}
