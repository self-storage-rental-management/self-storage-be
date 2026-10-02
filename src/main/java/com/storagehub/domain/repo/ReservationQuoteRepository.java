package com.storagehub.domain.repo;

import com.storagehub.domain.model.ReservationQuote;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationQuoteRepository extends JpaRepository<ReservationQuote, UUID> {
    Optional<ReservationQuote> findByIdAndCustomer_Id(UUID id, UUID customerId);
}
