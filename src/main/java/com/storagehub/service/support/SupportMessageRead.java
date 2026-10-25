package com.storagehub.service.support;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity @Table(name="support_message_reads") @IdClass(SupportMessageRead.Key.class)
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SupportMessageRead {
    @Id private UUID readerId;
    @Id private UUID messageId;
    private UUID ticketId;
    private Instant readAt;
    public SupportMessageRead(UUID reader,UUID message,UUID ticket,Instant at){readerId=reader;messageId=message;ticketId=ticket;readAt=at;}
    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable { public UUID readerId;public UUID messageId; }
}
