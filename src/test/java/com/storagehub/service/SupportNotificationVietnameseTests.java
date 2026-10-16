package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.NotificationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.support.SupportNotificationText;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

class SupportNotificationVietnameseTests {
    @Test void knownLegacySupportTitlesAreVietnamese(){
        var examples=Map.of(
            "Support assigned","Yêu cầu hỗ trợ đã được phân công",
            "Manager replied to Support","Quản lý đã gửi tin nhắn hỗ trợ",
            "Customer replied to Support","Khách hàng đã gửi tin nhắn hỗ trợ",
            "Staff replied to Support","Nhân viên đã phản hồi yêu cầu hỗ trợ",
            "New Support ticket","Có yêu cầu hỗ trợ mới",
            "Support resolved","Nhân viên đã báo xử lý xong yêu cầu hỗ trợ");
        examples.forEach((oldTitle,translated)->assertThat(SupportNotificationText.title(oldTitle)).isEqualTo(translated));
    }
    @Test void storedEnglishNotificationsAreTranslatedOnListAndReadWithoutRewritingHistory(){
        var repository=mock(NotificationRepository.class);var users=mock(UserRepository.class);
        var service=new NotificationService(repository,users);var id=UUID.randomUUID();var user=new User();
        ReflectionTestUtils.setField(user,"id",id);
        var notice=new Notification();notice.setUser(user);notice.setType(NotificationType.SUPPORT);
        notice.setTitle("Manager replied to Support");notice.setContent("Yêu cầu hỗ trợ");notice.setRead(true);
        ReflectionTestUtils.setField(notice,"id",UUID.randomUUID());
        var actor=new ActorPrincipal(id,UUID.randomUUID(),Set.of(RoleCode.STAFF),Set.of(),Map.of());
        when(repository.search(eq(id),isNull(),any())).thenReturn(new PageImpl<>(List.of(notice)));
        when(repository.findOwnedById(notice.getId(),id)).thenReturn(Optional.of(notice));
        assertThat(service.list(actor,null,0,50,"test").data().getFirst().title()).isEqualTo("Quản lý đã gửi tin nhắn hỗ trợ");
        assertThat(service.markRead(actor,notice.getId()).title()).isEqualTo("Quản lý đã gửi tin nhắn hỗ trợ");
        assertThat(notice.getTitle()).isEqualTo("Manager replied to Support");
        verify(repository,never()).save(any());verify(repository,never()).saveAndFlush(any());
    }
    @Test void newSupportNotificationsAreStoredInVietnamese(){
        var repository=mock(NotificationRepository.class);var users=mock(UserRepository.class);
        var service=new NotificationService(repository,users);var user=new User();var id=UUID.randomUUID();
        ReflectionTestUtils.setField(user,"id",id);when(users.findById(id)).thenReturn(Optional.of(user));
        when(repository.save(any(Notification.class))).thenAnswer(call->call.getArgument(0));
        var notice=service.createNotification(id,NotificationType.SUPPORT,"Customer replied to Support","Yêu cầu hỗ trợ",UUID.randomUUID());
        assertThat(notice.getTitle()).isEqualTo("Khách hàng đã gửi tin nhắn hỗ trợ");
    }
}
