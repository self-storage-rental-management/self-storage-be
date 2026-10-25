package com.storagehub.service.support;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity @Table(name="support_hidden_history") @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SupportHiddenHistory {
    @Id private UUID ticketId;
    private UUID customerId;
    private Instant hiddenAt;
    public SupportHiddenHistory(UUID ticket,UUID customer,Instant at){ticketId=ticket;customerId=customer;hiddenAt=at;}
}
