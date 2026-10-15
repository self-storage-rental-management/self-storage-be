package com.storagehub.service;

import com.storagehub.api.notification.NotificationResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Notification;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.NotificationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final DateTimeFormatter CHECK_IN_TIME_FORMATTER = DateTimeFormatter
        .ofPattern("dd/MM/yyyy HH:mm")
        .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Transactional
    public void notifyBusinessOfUnitTypeReview(com.storagehub.domain.model.UnitType type, UUID requestId) {
        for (User user : userRepository.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)) {
            createNotification(user.getId(), NotificationType.SYSTEM,
                "Yêu cầu duyệt thay đổi loại kho",
                type.getFacility().getName() + " · " + type.getCode() + " · Manager gửi thay đổi, chờ BO duyệt.",
                requestId);
        }
    }

    @Transactional
    public void notifyUnitTypeReviewResult(com.storagehub.domain.model.UnitTypeChangeRequest request) {
        createNotification(request.getRequestedBy(), NotificationType.SYSTEM,
            "APPROVED".equals(request.getStatus()) ? "BO đã duyệt thay đổi loại kho" : "BO từ chối thay đổi loại kho",
            request.getFacilityName() + " · " + request.getUnitTypeCode() +
                (request.getRejectionReason() == null ? "" : "\nLý do: " + request.getRejectionReason()), request.getUnitTypeId());
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(
        ActorPrincipal actor,
        Boolean isRead,
        int page,
        int size,
        String correlationId
    ) {
        PageRequest pageable = pageable(page, size);
        Page<NotificationResponse> result = notificationRepository.search(actor.userId(), isRead, pageable)
            .map(this::toResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional
    public NotificationResponse markRead(ActorPrincipal actor, UUID notificationId) {
        Notification notification = notificationRepository.findOwnedById(notificationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Notification was not found"));
        if (!notification.isRead()) {
            notification.setRead(true);
            notification = notificationRepository.saveAndFlush(notification);
        }
        return toResponse(notification);
    }

    @Transactional
    public int markAllRead(ActorPrincipal actor) {
        return notificationRepository.markAllAsRead(actor.userId());
    }

    @Transactional
    public Notification createNotification(
        UUID userId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId
    ) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiExceptions.notFound("User was not found"));
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setTitle(type==NotificationType.SUPPORT?com.storagehub.service.support.SupportNotificationText.title(title):title);
        notification.setContent(content);
        notification.setRelatedEntityId(relatedEntityId);
        notification.setRead(false);
        return notificationRepository.save(notification);
    }

    @Transactional
    public void notifyCustomerRentalEventOnce(UUID customerId, UUID rentalId, String title, String content) {
        if (!notificationRepository.existsByUser_IdAndRelatedEntityIdAndTypeAndTitleAndContent(
            customerId, rentalId, NotificationType.OVERDUE, title, content)) {
            createNotification(customerId, NotificationType.OVERDUE, title, content, rentalId);
        }
    }

    /**
     * Publishes an operational notification to every active Staff and Manager
     * who can work with or monitor the facility. The recipient set is built
     * from the persisted facility scopes, so users assigned to another
     * facility do not receive this reservation notification.
     */
    @Transactional
    public void createFacilityOperationsNotifications(
        UUID facilityId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId
    ) {
        createFacilityOperationsNotificationsExcept(facilityId, type, title, content, relatedEntityId, null);
    }

    @Transactional
    public void createFacilityOperationsNotificationsExcept(
        UUID facilityId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId,
        UUID excludedUserId
    ) {
        Set<UUID> recipientIds = new LinkedHashSet<>();
        recipientIds.addAll(userRepository.findEligibleStaff(
            UserStatus.ACTIVE,
            RoleCode.STAFF,
            facilityId,
            List.of(FacilityScopeLevel.OPERATE)
        ).stream().map(User::getId).toList());
        recipientIds.addAll(userRepository.findEligibleStaff(
            UserStatus.ACTIVE,
            RoleCode.MANAGER,
            facilityId,
            List.of(FacilityScopeLevel.READ, FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
        ).stream().map(User::getId).toList());

        recipientIds.stream()
            .filter(recipientId -> !Objects.equals(recipientId, excludedUserId))
            .forEach(recipientId -> createNotification(recipientId, type, title, content, relatedEntityId));
    }

    @Transactional
    public void notifyStaffOfCheckInAppointmentChange(Reservation reservation, Instant previousAppointmentAt) {
        createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Khách hàng đã cập nhật lịch nhận kho",
            "Đơn " + reservation.getReservationCode() + " nhận kho lúc "
                + reservation.getAppointmentAt() + ".",
            reservation.getId()
        );
    }

    @Transactional
    public void notifyStaffOfReservationCancellation(Reservation reservation, boolean assignedUnitReleased) {
        createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.RESERVATION,
            "Đơn giữ kho đã được khách hàng hủy",
            "Đơn " + reservation.getReservationCode() + " đã được hủy."
                + (assignedUnitReleased ? " Kho đã được giải phóng." : ""),
            reservation.getId()
        );
    }

    private PageRequest pageable(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getUser().getId(),
            notification.getType(),
            notification.getType()==NotificationType.SUPPORT?com.storagehub.service.support.SupportNotificationText.title(notification.getTitle()):notification.getTitle(),
            notification.getContent(),
            notification.getRelatedEntityId(),
            notification.isRead(),
            notification.getCreatedAt()
        );
    }
}
