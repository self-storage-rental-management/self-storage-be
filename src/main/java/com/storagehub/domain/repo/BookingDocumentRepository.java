package com.storagehub.domain.repo;

import com.storagehub.domain.model.BookingDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingDocumentRepository extends JpaRepository<BookingDocument, UUID> {
    Optional<BookingDocument> findByReservation_Id(UUID reservationId);

    Optional<BookingDocument> findByReservation_IdAndReservation_Customer_Id(
        UUID reservationId,
        UUID customerId
    );
}
