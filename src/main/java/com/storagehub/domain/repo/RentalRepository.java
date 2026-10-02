package com.storagehub.domain.repo;

import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RentalRepository extends JpaRepository<Rental, UUID> {

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
}
