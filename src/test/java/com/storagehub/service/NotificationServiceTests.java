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

    private User user(RoleCode role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
