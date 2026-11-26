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
    @org.springframework.beans.factory.annotation.Autowired private BusinessRecordCodes displayCodes;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.storagehub.service.renewal.RenewalDisplayIdentity renewalDisplayIdentity;
    private final UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.email.CustomerLifecycleEmailService customerEmails;

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
        Notification saved = saveNotification(userId, type, title, content, relatedEntityId, null);
        customerEmails.notification(saved);
        return saved;
    }

    @Transactional
    public Notification createNotification(
        UUID userId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId,
        RoleCode sourceRole
    ) {
        Notification saved = saveNotification(userId, type, title, content, relatedEntityId, sourceRole);
        customerEmails.notification(saved);
        return saved;
    }

    @Transactional
    public void createNotificationOnce(
        UUID userId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId
    ) {
        if (notificationRepository.existsMilestone(userId, relatedEntityId, title)) return;
        createNotification(userId, type, title, content, relatedEntityId);
    }

    @Transactional
    public boolean createNotificationIfNotSentSince(
        UUID userId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId,
        Instant since
    ) {
        if (notificationRepository.existsMilestoneSince(userId, relatedEntityId, type, title, since)) {
            return false;
        }
        createNotification(userId, type, title, content, relatedEntityId);
        return true;
    }

    @Transactional
    public boolean createNotificationIfNotSentSince(
        UUID userId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId,
        Instant since,
        RoleCode sourceRole
    ) {
        if (sourceRole == null) {
            return createNotificationIfNotSentSince(userId, type, title, content, relatedEntityId, since);
        }
        if (notificationRepository.existsMilestoneSinceForSourceRole(
            userId, relatedEntityId, type, title, sourceRole, since
        )) {
            return false;
        }
        createNotification(userId, type, title, content, relatedEntityId, sourceRole);
        return true;
    }

    /** The payment receipt already supplies the email for this notification. */
    @Transactional
    public void notifyRenewalDepositReceived(UUID customerId, UUID renewalId, String amount) {
        saveNotification(customerId, NotificationType.RENEWAL, "Đã nhận tiền cọc gia hạn qua VNPay",
            "VNPay đã xác nhận khoản cọc gia hạn " + amount + ". Bạn có thể theo dõi các bước tiếp theo trong hồ sơ gia hạn.",
            renewalId, null);
    }

    private Notification saveNotification(
        UUID userId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId,
        RoleCode sourceRole
    ) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiExceptions.notFound("User was not found"));
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setSourceRole(sourceRole);
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
    public void createFacilityOperationsNotificationsOnce(
        UUID facilityId,
        NotificationType type,
        String title,
        String content,
        UUID relatedEntityId
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
        recipientIds.forEach(recipientId -> createNotificationOnce(
            recipientId, type, title, content, relatedEntityId
        ));
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
    public void notifyManagersOfReturnIdentityReview(com.storagehub.domain.model.ReturnCase returned) {
        UUID facilityId = returned.getFacility().getId();
        Instant sessionStart = returned.getReturnReverificationStartedAt() == null
            ? returned.getReturnArrivedAt() : returned.getReturnReverificationStartedAt();
        String unit = returned.getStorageUnit().getCode();
        userRepository.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facilityId,
            List.of(FacilityScopeLevel.MANAGE)).forEach(manager ->
                createNotificationIfNotSentSince(manager.getId(), NotificationType.RETURN,
                    "Cần đối soát danh tính trả kho",
                    "Phiên xác minh của gian kho " + unit + " đã kết thúc. Mở hồ sơ trả kho để đối soát.",
                    returned.getId(), sessionStart));
    }

    @Transactional
    public void createBusinessNotifications(
        NotificationType type, String title, String content, UUID relatedEntityId
    ) {
        userRepository.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)
            .forEach(user -> createNotification(user.getId(), type, title, content, relatedEntityId));
    }

    @Transactional
    public void notifyStaffOfCheckInAppointmentChange(Reservation reservation, Instant previousAppointmentAt) {
        createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.RESERVATION,
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

    /** Resolves a notification's related reservation id to the order code shown to operators. */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.storagehub.domain.repo.ReservationRepository reservations;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.storagehub.domain.repo.ViolationRecoveryRepository violationRecoveries;

    /**
     * The staff bell is a queue across every reservation in a facility, so a notification that
     * does not name its order leaves the operator unable to tell which case to open. Check-in
     * and reservation notifications carry the reservation id, so resolve it to the order code.
     */
    private String reservationCodeFor(UUID reservationId) {
        if (reservationId == null || reservations == null) return null;
        return reservations.findById(reservationId)
            .map(Reservation::getReservationCode)
            .filter(code -> !code.isBlank())
            .orElse(null);
    }

    private NotificationResponse toResponse(Notification notification) {
        String content=notification.getContent();
        content = displayContent(content, notification.getTitle());
        if (displayCodes != null) content = displayCodes.noticeText(content);
        UUID related=notification.getRelatedEntityId();
        String relatedEntityType = null;
        if (related != null && notification.getType() == NotificationType.RETURN && violationRecoveries != null
            && violationRecoveries.existsById(related)) relatedEntityType = "VIOLATION_RECOVERY";
        if(related!=null){
            String key=switch(notification.getType()){
                case RENEWAL -> null;
                case SUPPORT -> "HT:"+related;
                case RETURN -> "TK:"+related;
                case OVERDUE -> "RENTAL_TERM:"+related;
                default -> null;
            };
            String code=notification.getType()==NotificationType.RENEWAL&&renewalDisplayIdentity!=null
                ?renewalDisplayIdentity.find(related).map(com.storagehub.service.renewal.RenewalDisplayIdentity.Identity::label).orElse(null)
                :key==null||displayCodes==null?null:displayCodes.find(key);
            if(code==null&&(notification.getType()==NotificationType.CHECKIN
                ||notification.getType()==NotificationType.RESERVATION)){
                code=reservationCodeFor(related);
            }
            if(code!=null&&(content==null||!content.contains(code)))content=code+" · "+(content==null?"":content);
        }
        return new NotificationResponse(
            notification.getId(),
            notification.getUser().getId(),
            notification.getType(),
            notification.getType()==NotificationType.SUPPORT?com.storagehub.service.support.SupportNotificationText.title(notification.getTitle()):displayTitle(notification.getTitle()),
            content,
            notification.getRelatedEntityId(),
            notification.isRead(),
            notification.getCreatedAt(),
            relatedEntityType
        );
    }

    // Lifecycle titles are also milestone identifiers, so normalize them only for display.
    private static String displayTitle(String title) {
        if (title == null) return null;
        return switch (title) {
            case "Nhắc nhở: Hợp đồng thuê kho sắp hết hạn (còn 14 ngày)" -> "Hợp đồng thuê kho còn 14 ngày";
            case "Cảnh báo: Hợp đồng thuê kho sắp hết hạn (còn 3 ngày)" -> "Hợp đồng thuê kho còn 3 ngày";
            case "Thông báo: Hợp đồng thuê kho đã hết hạn" -> "Hợp đồng thuê kho đã hết hạn";
            case "CẢNH BÁO BÀN GIAO: Hợp đồng hết hạn nhưng kho chưa được bàn giao" -> "Hợp đồng hết hạn, khách chưa trả kho";
            case "Nhắc nhở thanh toán: Khoản phí sắp đến hạn (D-3)" -> "Khoản thanh toán sắp đến hạn";
            case "Thông báo quá hạn: Khoản phí đến hạn thanh toán (D+1)" -> "Khoản thanh toán đã quá hạn 1 ngày";
            case "Thông báo: Kết thúc thời gian gia hạn thanh toán (D+3) - Đưa vào diện theo dõi" -> "Đã hết thời gian gia hạn thanh toán";
            case "THEO DÕI NỢ: Khoản phải thu quá hạn 3 ngày (Hết ân hạn)" -> "Khoản thanh toán quá hạn 3 ngày";
            case "Cảnh báo quá hạn kéo dài (D+7): Chuyển hồ sơ Manager xem xét" -> "Khoản thanh toán quá hạn từ 7 ngày";
            case "CẦN MANAGER XEM XÉT (D+7): Hồ sơ quá hạn thanh toán kéo dài" -> "Cần xem xét khoản thanh toán quá hạn";
            default -> title;
        };
    }

    private static String displayContent(String content, String title) {
        if (content == null) return null;
        if ("CẢNH BÁO BÀN GIAO: Hợp đồng hết hạn nhưng kho chưa được bàn giao".equals(title)) {
            content = content.replaceFirst("^Kho (.+) đã hết hạn vào ngày (\\d{4}-\\d{2}-\\d{2}) nhưng chưa được bàn giao\\. Cần theo dõi kiểm tra hiện trạng\\.$",
                "Hợp đồng thuê kho $1 đã hết hạn ngày $2. Khách hàng chưa trả kho. Vui lòng kiểm tra tình trạng kho.");
        }
        if (title == null || title.equals(displayTitle(title))) return content;
        var matcher = java.util.regex.Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?![\\dT])").matcher(content);
        return matcher.replaceAll("$3/$2/$1");
    }

}
