package com.storagehub.service;

import com.storagehub.api.notification.NotificationResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Notification;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.RoleCode;
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
    public void notifyBusinessOfUnitTypeChange(com.storagehub.domain.model.UnitType type, boolean technical) {
        for (User user : userRepository.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)) {
            createNotification(user.getId(), NotificationType.SYSTEM,
                technical ? "Loại kho cần BO duyệt lại" : "Manager cập nhật ảnh hoặc thông tin loại kho",
                type.getFacility().getName() + " · " + type.getCode() + " · " +
                    (technical ? "Thông số kỹ thuật thay đổi, đang chờ duyệt." : "Thông tin đã cập nhật, BO kiểm tra thay đổi; kho vẫn được công bố."),
                type.getId());
        }
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
        notification.setTitle(title);
        notification.setContent(content);
        notification.setRelatedEntityId(relatedEntityId);
        notification.setRead(false);
        return notificationRepository.save(notification);
    }

    @Transactional
    public void notifyStaffOfCheckInAppointmentChange(
        Reservation reservation,
        Instant previousAppointmentAt
    ) {
        Instant appointmentAt = reservation.getAppointmentAt();
        if (Objects.equals(previousAppointmentAt, appointmentAt)) {
            return;
        }

        String previousTime = previousAppointmentAt == null
            ? "Chưa đặt lịch"
            : CHECK_IN_TIME_FORMATTER.format(previousAppointmentAt);
        String newTime = CHECK_IN_TIME_FORMATTER.format(appointmentAt);
        String content = reservation.getReservationCode() + " · " + previousTime + " → " + newTime;

        var notifications = userRepository.findEligibleStaff(
            UserStatus.ACTIVE,
            RoleCode.STAFF,
            reservation.getFacility().getId(),
            EnumSet.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
        ).stream().map(staff -> {
            Notification notification = new Notification();
            notification.setUser(staff);
            notification.setType(NotificationType.RESERVATION);
            notification.setTitle("Khách hàng đã thay đổi lịch nhận kho");
            notification.setContent(content);
            notification.setRelatedEntityId(reservation.getId());
            notification.setRead(false);
            return notification;
        }).toList();

        notificationRepository.saveAll(notifications);
    }

    @Transactional
    public void notifyStaffOfReservationCancellation(Reservation reservation, boolean unitReleased) {
        String content = "Đơn " + reservation.getReservationCode() + " đã được khách hàng hủy. "
            + (unitReleased
                ? "Gian kho đã được tự động giải phóng và chuyển về trạng thái sẵn sàng."
                : "Suất giữ kho đã được giải phóng.");
        var notifications = userRepository.findEligibleStaff(
            UserStatus.ACTIVE,
            RoleCode.STAFF,
            reservation.getFacility().getId(),
            EnumSet.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
        ).stream().map(staff -> {
            Notification notification = new Notification();
            notification.setUser(staff);
            notification.setType(NotificationType.RESERVATION);
            notification.setTitle("Khách hàng đã hủy giữ kho");
            notification.setContent(content);
            notification.setRelatedEntityId(reservation.getId());
            notification.setRead(false);
            return notification;
        }).toList();
        notificationRepository.saveAll(notifications);
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
            notification.getTitle(),
            notification.getContent(),
            notification.getRelatedEntityId(),
            notification.isRead(),
            notification.getCreatedAt()
        );
    }
}
