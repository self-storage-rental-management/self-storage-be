package com.storagehub.domain.repo;

import com.storagehub.domain.model.Notification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @EntityGraph(attributePaths = "user")
    @Query("""
        select notification from Notification notification
        where notification.user.id = :userId
          and (:isRead is null or notification.readFlag = :isRead)
        """)
    Page<Notification> search(
        @Param("userId") UUID userId,
        @Param("isRead") Boolean isRead,
        Pageable pageable
    );

    @EntityGraph(attributePaths = "user")
    @Query("""
        select notification from Notification notification
        where notification.id = :notificationId and notification.user.id = :userId
        """)
    Optional<Notification> findOwnedById(
        @Param("notificationId") UUID notificationId,
        @Param("userId") UUID userId
    );

    @Modifying
    @Query("""
        update Notification notification
        set notification.readFlag = true
        where notification.user.id = :userId and notification.readFlag = false
        """)
    int markAllAsRead(@Param("userId") UUID userId);
}
