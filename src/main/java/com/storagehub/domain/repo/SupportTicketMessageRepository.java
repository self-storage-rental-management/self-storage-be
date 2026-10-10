package com.storagehub.domain.repo;

import com.storagehub.domain.model.SupportTicketMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketMessageRepository extends JpaRepository<SupportTicketMessage, UUID> {
    List<SupportTicketMessage> findByTicket_IdOrderByCreatedAtAsc(UUID ticketId);
}
