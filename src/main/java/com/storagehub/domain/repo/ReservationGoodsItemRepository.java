package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReservationGoodsItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationGoodsItemRepository extends JpaRepository<ReservationGoodsItem, UUID> {
    List<ReservationGoodsItem> findAllByReservation_IdOrderByCreatedAtAsc(UUID reservationId);
}
