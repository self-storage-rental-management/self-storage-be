package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    /**
     * Role that initiated an operational reminder. Ordinary notifications
     * leave this empty. It lets role-specific reminder cooldowns be enforced
     * without exposing the sender role to the recipient.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_role", length = 32)
    private RoleCode sourceRole;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column
    private UUID relatedEntityId;

    @Column(name = "is_read", nullable = false)
    private boolean readFlag = false;

    public boolean isRead() {
        return readFlag;
    }

    public void setRead(boolean read) {
        this.readFlag = read;
    }
}
