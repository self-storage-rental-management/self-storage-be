package com.storagehub.domain.repo;

import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitAssignmentRepository extends JpaRepository<UnitAssignment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from UnitAssignment a where a.id = :id")
    Optional<UnitAssignment> findByIdForUpdate(@Param("id") UUID id);

    Optional<UnitAssignment> findByReservation_IdAndStatus(UUID reservationId, UnitAssignmentStatus status);

    Optional<UnitAssignment> findFirstByReservation_IdOrderByAssignedAtDesc(UUID reservationId);

    boolean existsByStorageUnit_IdAndStatus(UUID storageUnitId, UnitAssignmentStatus status);

    Optional<UnitAssignment> findByCancelledBy_IdAndCancellationIdempotencyKey(
        UUID cancelledById,
        String cancellationIdempotencyKey
    );

    @Query("""
        select a from UnitAssignment a
        join a.reservation r
        join a.storageUnit u
        where a.status = com.storagehub.domain.model.UnitAssignmentStatus.ACTIVE
          and r.status = com.storagehub.domain.model.ReservationStatus.CANCELLED
          and (:facilityId is null or r.facility.id = :facilityId)
          and (:unitTypeId is null or r.unitType.id = :unitTypeId)
          and (:scoped = false or r.facility.id in :facilityIds)
          and (:q is null or lower(r.reservationCode) like lower(concat('%', :q, '%'))
               or lower(u.code) like lower(concat('%', :q, '%'))
               or lower(r.customer.email) like lower(concat('%', :q, '%'))
               or lower(r.customer.fullName) like lower(concat('%', :q, '%')))
          and (
            :blocked is null
            or (
              :blocked = false
              and r.assignedUnit.id = u.id
              and u.status in (
                com.storagehub.domain.model.StorageUnitStatus.reserved,
                com.storagehub.domain.model.StorageUnitStatus.assigned
              )
              and not exists (
                select c.id from CheckIn c
                where c.reservation.id = r.id
                  and c.status = com.storagehub.domain.model.CheckInStatus.completed
              )
              and not exists (
                select rental.id from Rental rental
                where rental.status = com.storagehub.domain.model.RentalStatus.active
                  and (rental.reservation.id = r.id or rental.storageUnit.id = u.id)
              )
            )
            or (
              :blocked = true
              and (
                r.assignedUnit is null
                or r.assignedUnit.id <> u.id
                or u.status not in (
                  com.storagehub.domain.model.StorageUnitStatus.reserved,
                  com.storagehub.domain.model.StorageUnitStatus.assigned
                )
                or exists (
                  select c.id from CheckIn c
                  where c.reservation.id = r.id
                    and c.status = com.storagehub.domain.model.CheckInStatus.completed
                )
                or exists (
                  select rental.id from Rental rental
                  where rental.status = com.storagehub.domain.model.RentalStatus.active
                    and (rental.reservation.id = r.id or rental.storageUnit.id = u.id)
                )
              )
            )
          )
        """)
    Page<UnitAssignment> findPendingReleases(
        @Param("facilityId") UUID facilityId,
        @Param("unitTypeId") UUID unitTypeId,
        @Param("q") String q,
        @Param("blocked") Boolean blocked,
        @Param("scoped") boolean scoped,
        @Param("facilityIds") Collection<UUID> facilityIds,
        Pageable pageable
    );
}
