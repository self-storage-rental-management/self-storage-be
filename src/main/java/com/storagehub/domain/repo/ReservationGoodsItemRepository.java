package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReservationGoodsItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationGoodsItemRepository extends JpaRepository<ReservationGoodsItem, UUID> {
    List<ReservationGoodsItem> findAllByReservation_IdOrderByCreatedAtAsc(UUID reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ReservationGoodsItem item where item.id = :id")
    Optional<ReservationGoodsItem> findByIdForUpdate(@Param("id") UUID id);
}
