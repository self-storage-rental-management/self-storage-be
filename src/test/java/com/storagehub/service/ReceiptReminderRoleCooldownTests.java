package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.NotificationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.service.email.CustomerLifecycleEmailService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReceiptReminderRoleCooldownTests {
    @Test
    void staffCooldownDoesNotBlockManagerAndSuccessfulReminderQueuesEmail() {
        var notices = mock(NotificationRepository.class);
        var users = mock(UserRepository.class);
        var emails = mock(CustomerLifecycleEmailService.class);
        var service = new NotificationService(notices, users);
        ReflectionTestUtils.setField(service, "customerEmails", emails);
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant since = Instant.parse("2026-11-23T01:00:00Z");
        String title = "Nhắc khách xác nhận nhận kho";
        when(notices.existsMilestoneSinceForSourceRole(customerId, orderId,
            NotificationType.CHECKIN, title, RoleCode.STAFF, since)).thenReturn(true);
        when(users.findById(customerId)).thenReturn(Optional.of(new User()));
        when(notices.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.createNotificationIfNotSentSince(customerId, NotificationType.CHECKIN,
            title, "Nội dung nhắc", orderId, since, RoleCode.STAFF)).isFalse();
        assertThat(service.createNotificationIfNotSentSince(customerId, NotificationType.CHECKIN,
            title, "Nội dung nhắc", orderId, since, RoleCode.MANAGER)).isTrue();
        verify(emails).notification(argThat(notice -> notice.getSourceRole() == RoleCode.MANAGER));
        verify(notices, times(1)).save(any(Notification.class));
    }
}
