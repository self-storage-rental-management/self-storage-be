package com.storagehub.domain.repo;

import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RentalRepository extends JpaRepository<Rental, UUID>, JpaSpecificationExecutor<Rental> {
    @Query("""
        select (count(r) > 0) from Rental r where r.storageUnit.id = :unitId and r.id <> :rentalId
          and r.actualReturnedAt is null and r.status <> com.storagehub.domain.model.RentalStatus.completed
        """)
    boolean existsOtherUnreturnedRental(@Param("unitId") UUID unitId, @Param("rentalId") UUID rentalId);

    @Query("""
        select r.id from Rental r where r.id in :ids and (
          exists (select rc.id from ReturnCase rc where rc.rental = r
            and rc.returnType = com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH)
          or exists (select c.id from CheckIn c where c.reservation = r.reservation and (
            c.inventoryResolutionStatus = com.storagehub.domain.model.InventoryResolutionStatus.RETURN_REQUESTED
            or (c.inventoryResolutionStatus in (com.storagehub.domain.model.InventoryResolutionStatus.PENDING_CUSTOMER,
                                               com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION)
              and r.inventoryRemovalDeadlineAt is not null and c.inventoryFullFit = false
              and c.inventoryPartialFitAvailable = false))))
        """)
    java.util.Set<UUID> findInventoryMismatchReturnIds(@Param("ids") java.util.Collection<UUID> ids);

    @Query("""
        select (count(r) > 0) from Rental r
        where r.status = :status
          and (r.reservation.id = :reservationId or r.storageUnit.id = :storageUnitId)
        """)
    boolean existsActiveRental(
        @Param("reservationId") UUID reservationId,
        @Param("storageUnitId") UUID storageUnitId,
        @Param("status") RentalStatus status
    );

    java.util.Optional<Rental> findByIdAndCustomer_Id(UUID id, UUID customerId);

    Optional<Rental> findByReservation_Id(UUID reservationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rental r where r.reservation.id = :reservationId")
    Optional<Rental> findByReservationIdForUpdate(@Param("reservationId") UUID reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rental r where r.id = :id")
    Optional<Rental> findByIdForUpdate(@Param("id") UUID id);

    org.springframework.data.domain.Page<Rental> findByCustomer_Id(UUID customerId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Rental> findByCustomer_IdAndStatus(
        UUID customerId,
        RentalStatus status,
        org.springframework.data.domain.Pageable pageable
    );

    java.util.List<Rental> findByFacility_Id(UUID facilityId);

    java.util.List<Rental> findByFacility_IdAndStatus(UUID facilityId, RentalStatus status);

    @Query("select r.id from Rental r where r.status = :status and r.actualReturnedAt is null")
    java.util.List<UUID> findActiveUnreturnedIds(@Param("status") RentalStatus status);

    long countByFacility_IdAndStatus(UUID facilityId, RentalStatus status);

    java.util.List<Rental> findByStatus(RentalStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    java.util.List<Rental> findByStatusAndSetupEndsAtIsNotNull(RentalStatus status);

    java.util.List<Rental> findByStatusAndInventoryPendingTrue(RentalStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    java.util.List<Rental> findByInventoryPendingTrueAndStatusIn(
        java.util.Collection<RentalStatus> statuses
    );

    long countByStatus(RentalStatus status);

    @Query("""
        select count(r) from Rental r
        where r.facility.id = :facilityId
          and (:excludeRentalId is null or r.id <> :excludeRentalId)
          and r.inventoryAppointmentAt >= :slotStart
          and r.inventoryAppointmentAt < :slotEnd
          and (r.appointmentStatus is null or r.appointmentStatus = 'ACTIVE')
          and r.completedAt is null
          and r.actualReturnedAt is null
          and r.inventoryEscalatedAt is null
        """)
    long countActiveAppointmentsInSlot(
        @Param("facilityId") UUID facilityId,
        @Param("excludeRentalId") UUID excludeRentalId,
        @Param("slotStart") java.time.Instant slotStart,
        @Param("slotEnd") java.time.Instant slotEnd
    );
}
