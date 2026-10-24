package com.storagehub.service.support;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="customer_chat_sessions") @Getter @Setter
public class CustomerChatSession {
    @Id private UUID customerId;
    private long revision;
    @Column(columnDefinition="LONGTEXT",nullable=false) private String messagesJson;
    private UUID ticketId;
    private boolean autoReturn;
    private Instant expiresAt;
    private UUID mutationId;
    private String mutationHash;
}
