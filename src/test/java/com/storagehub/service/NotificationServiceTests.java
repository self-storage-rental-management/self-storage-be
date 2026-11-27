package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.Notification;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.NotificationRepository;
import com.storagehub.domain.repo.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class NotificationServiceTests {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;

    private NotificationService service;
    private UUID facilityId;
    private UUID reservationId;
    private User staff;
    private User manager;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository, userRepository);
        ReflectionTestUtils.setField(service, "customerEmails",
            org.mockito.Mockito.mock(com.storagehub.service.email.CustomerLifecycleEmailService.class));
        facilityId = UUID.randomUUID();
        reservationId = UUID.randomUUID();
        staff = user(RoleCode.STAFF);
        manager = user(RoleCode.MANAGER);
        when(userRepository.findEligibleStaff(
            UserStatus.ACTIVE, RoleCode.STAFF, facilityId, List.of(FacilityScopeLevel.OPERATE)
        )).thenReturn(List.of(staff));
        when(userRepository.findEligibleStaff(
            UserStatus.ACTIVE, RoleCode.MANAGER, facilityId,
            List.of(FacilityScopeLevel.READ, FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
        )).thenReturn(List.of(manager));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(notificationRepository.save(any(Notification.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void sendsFacilityNotificationToStaffAndManagerScopes() {
        service.createFacilityOperationsNotifications(
            facilityId,
            NotificationType.RESERVATION,
            "Có đơn chờ phân kho",
            "Đơn đang chờ xử lý",
            reservationId
        );

        ArgumentCaptor<Notification> notifications = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.times(2)).save(notifications.capture());

        assertThat(notifications.getAllValues())
            .extracting(item -> item.getUser().getId())
            .containsExactlyInAnyOrder(staff.getId(), manager.getId());
        assertThat(notifications.getAllValues())
            .allSatisfy(item -> {
                assertThat(item.getType()).isEqualTo(NotificationType.RESERVATION);
                assertThat(item.getRelatedEntityId()).isEqualTo(reservationId);
                assertThat(item.isRead()).isFalse();
            });
    }

    /**
     * The staff bell is a queue across every reservation in the facility. A check-in notice that
     * does not name its order leaves the operator unable to tell which case to open.
     */
    @Test
    void checkInNotificationNamesTheOrderItIsAbout() {
        assertThat(noticeContent("RSV-TEST-06"))
            .startsWith("RSV-TEST-06 · ")
            .contains("Đã qua 30 phút kể từ giờ hẹn.");
    }

    @Test
    void keepsTheNoticeReadableWhenTheOrderCodeCannotBeResolved() {
        assertThat(noticeContent(null)).isEqualTo("Đã qua 30 phút kể từ giờ hẹn.");
    }

    /** Renders one check-in notification whose reservation resolves to the given order code. */
    private String noticeContent(String reservationCode) {
        var reservations = org.mockito.Mockito.mock(com.storagehub.domain.repo.ReservationRepository.class);
        if (reservationCode == null) {
            when(reservations.findById(reservationId)).thenReturn(Optional.empty());
        } else {
            var reservation = new com.storagehub.domain.model.Reservation();
            ReflectionTestUtils.setField(reservation, "id", reservationId);
            ReflectionTestUtils.setField(reservation, "reservationCode", reservationCode);
            when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        }
        ReflectionTestUtils.setField(service, "reservations", reservations);

        var user = user(RoleCode.STAFF);
        var notice = new Notification();
        notice.setUser(user);
        notice.setType(NotificationType.CHECKIN);
        notice.setTitle("Cần xác nhận có mặt sau giờ hẹn");
        notice.setContent("Đã qua 30 phút kể từ giờ hẹn.");
        notice.setRelatedEntityId(reservationId);

        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(notificationRepository.search(any(), any(), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(notice), pageable, 1));

        var actor = new com.storagehub.security.ActorPrincipal(
            user.getId(), null, java.util.Set.of(RoleCode.STAFF), java.util.Set.of(), java.util.Map.of());
        return service.list(actor, null, 0, 20, "corr").data().getFirst().content();
    }

    @Test
    void recoveryNotificationCarriesRoutingTypeAndDoesNotDuplicateExistingAssignmentNotice() {
        var recoveries = org.mockito.Mockito.mock(com.storagehub.domain.repo.ViolationRecoveryRepository.class);
        ReflectionTestUtils.setField(service, "violationRecoveries", recoveries);
        var recoveryId = UUID.randomUUID();
        when(recoveries.existsById(recoveryId)).thenReturn(true);
        var notice = new Notification();
        notice.setUser(manager);
        notice.setType(NotificationType.RETURN);
        notice.setTitle("Cần phân công nhân viên thu hồi");
        notice.setContent("Đơn RSV-TEST-10 · Gian TEST-M-10. Mở Nhân viên & Nhiệm vụ để phân công.");
        notice.setRelatedEntityId(recoveryId);
        when(notificationRepository.search(any(), any(), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(notice)));
        var actor = new com.storagehub.security.ActorPrincipal(manager.getId(), null, java.util.Set.of(RoleCode.MANAGER),
            java.util.Set.of(), java.util.Map.of(facilityId, FacilityScopeLevel.MANAGE));
        var response = service.list(actor, null, 0, 20, "corr").data().getFirst();
        assertThat(response.relatedEntityType()).isEqualTo("VIOLATION_RECOVERY");
        assertThat(response.content()).contains("RSV-TEST-10", "Nhân viên & Nhiệm vụ");
        when(notificationRepository.existsMilestone(manager.getId(), recoveryId, notice.getTitle())).thenReturn(true);
        service.createNotificationOnce(manager.getId(), NotificationType.RETURN, notice.getTitle(), notice.getContent(), recoveryId);
        verify(notificationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void legacyHandoverNoticeDoesNotAskCustomerToConfirmOrExposeLockReference() {
        var notice = new Notification();
        notice.setUser(staff);
        notice.setType(NotificationType.CHECKIN);
        notice.setTitle("Đã bàn giao kho, chờ bạn xác nhận");
        notice.setContent("RSV-TEST · Gian kho TEST-S-001 đã được bàn giao. Mã ổ khóa đã bàn giao: LOCK-001. Vui lòng kiểm tra mã và xác nhận đã nhận kho trên tài khoản khách hàng để bắt đầu thời gian chuẩn bị.");
        notice.setRelatedEntityId(reservationId);
        when(notificationRepository.search(any(), any(), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(notice)));
        var actor = new com.storagehub.security.ActorPrincipal(staff.getId(), null,
            java.util.Set.of(RoleCode.CUSTOMER), java.util.Set.of(), java.util.Map.of());
        var response = service.list(actor, null, 0, 20, "corr").data().getFirst();
        assertThat(response.title()).isEqualTo("Đã ghi nhận bàn giao kho");
        assertThat(response.content()).contains("Bạn không cần xác nhận nhận kho", "chuẩn bị xong")
            .doesNotContain("LOCK-001", "Vui lòng kiểm tra mã", "để bắt đầu thời gian chuẩn bị");
        assertThat(notice.getTitle()).isEqualTo("Đã bàn giao kho, chờ bạn xác nhận");
        verify(notificationRepository, org.mockito.Mockito.never()).save(any());
    }

    private User user(RoleCode role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
