package com.storagehub.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.storagehub.config.EmailProperties;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.service.BusinessRecordCodes;
import com.storagehub.service.IntakeTimelineCalculator;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerRecoveryEmailTests {
    @Test
    @SuppressWarnings("unchecked")
    void recoveryNoticeQueuesCustomerEmailWithTheCorrectOrderAndWebLink() {
        var em = mock(EntityManager.class);
        var mail = mock(TransactionalEmailService.class);
        var properties = new EmailProperties();
        properties.setFrontendBaseUrl("http://localhost:8443");
        var snapshots = mock(ReservationPricingSnapshotRepository.class);
        var service = new CustomerLifecycleEmailService(em, mail, properties,
            mock(RentalPeriodResolver.class), snapshots);
        var codes = mock(BusinessRecordCodes.class);
        when(codes.noticeText(anyString())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(service, "displayCodes", codes);
        var customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        customer.setEmail("customer@example.test");
        customer.setFullName("Khách thử nghiệm");
        var role = new Role();
        role.setCode(RoleCode.CUSTOMER);
        customer.getRoles().add(role);
        var reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setCustomer(customer);
        reservation.setReservationCode("RSV-TEST-10");
        var rental = new Rental();
        rental.setCustomer(customer);
        rental.setReservation(reservation);
        rental.setStartDate(LocalDate.of(2026, 11, 1));
        var recovery = new ViolationRecovery();
        recovery.setRental(rental);
        var recoveryId = UUID.randomUUID();
        when(em.find(ViolationRecovery.class, recoveryId)).thenReturn(recovery);
        when(snapshots.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());
        var checkIns = (TypedQuery<CheckIn>) mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(CheckIn.class))).thenReturn(checkIns);
        when(checkIns.setParameter(anyString(), any())).thenReturn(checkIns);
        when(checkIns.setMaxResults(anyInt())).thenReturn(checkIns);
        when(checkIns.getResultList()).thenReturn(List.of());
        var notice = new Notification();
        ReflectionTestUtils.setField(notice, "id", UUID.randomUUID());
        notice.setUser(customer);
        notice.setRelatedEntityId(recoveryId);
        notice.setType(NotificationType.RETURN);
        notice.setTitle("Thông báo thu hồi kho");
        notice.setContent("Đơn RSV-TEST-10 · Gian TEST-M-10. Lịch thu hồi: 10:00 27/11/2026.");

        service.notification(notice);

        var payload = ArgumentCaptor.forClass(Map.class);
        verify(mail).queueTemplate(eq(customer.getId()), eq(customer.getEmail()),
            eq("StorageHub - Thông báo thu hồi kho"), eq("customer-lifecycle"), payload.capture(),
            eq("CUSTOMER_TRANSACTIONAL_EMAIL_SENT"), eq("notification:" + notice.getId()));
        assertThat(payload.getValue().get("summary").toString()).contains("RSV-TEST-10", "27/11/2026");
        assertThat(payload.getValue().get("detailsUrl").toString()).contains("emailSection=rental-records", reservation.getId().toString());
        assertThat((Map<String, String>) payload.getValue().get("details")).containsEntry("Mã đơn giữ kho", "RSV-TEST-10");
        assertThat((Map<String, String>) payload.getValue().get("details")).doesNotContainKeys("Lịch nhận kho", "Giấy tờ khi nhận kho");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missedInventoryReminderEmailOmitsCheckInHandoverAndShowsInventoryFields() {
        var em = mock(EntityManager.class);
        var mail = mock(TransactionalEmailService.class);
        var properties = new EmailProperties();
        properties.setFrontendBaseUrl("http://localhost:8443");
        var snapshots = mock(ReservationPricingSnapshotRepository.class);
        var periods = mock(RentalPeriodResolver.class);
        var service = new CustomerLifecycleEmailService(em, mail, properties, periods, snapshots);
        var codes = mock(BusinessRecordCodes.class);
        when(codes.noticeText(anyString())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(service, "displayCodes", codes);

        var customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        customer.setEmail("customer@example.test");
        customer.setFullName("Thanh Thao");
        var role = new Role();
        role.setCode(RoleCode.CUSTOMER);
        customer.getRoles().add(role);

        var reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setCustomer(customer);
        reservation.setReservationCode("RSV-TEST-05");
        reservation.setAppointmentAt(Instant.parse("2026-11-23T14:56:00Z"));
        when(em.find(Reservation.class, reservation.getId())).thenReturn(reservation);

        var rental = new Rental();
        ReflectionTestUtils.setField(rental, "id", UUID.randomUUID());
        rental.setCustomer(customer);
        rental.setReservation(reservation);
        rental.setStartDate(LocalDate.of(2026, 11, 24));
        rental.setMoveInCompletedAt(Instant.parse("2026-11-23T15:26:00Z"));
        rental.setInventoryAppointmentAt(Instant.parse("2026-11-26T10:00:00Z"));
        rental.setAppointmentStatus(IntakeTimelineCalculator.APPT_MISSED);
        rental.setInventoryFinalDeadlineAt(Instant.parse("2026-11-30T15:26:00Z"));

        var rentalQuery = (TypedQuery<Rental>) mock(TypedQuery.class);
        when(em.createQuery(contains("from Rental r"), eq(Rental.class))).thenReturn(rentalQuery);
        when(rentalQuery.setParameter(anyString(), any())).thenReturn(rentalQuery);
        when(rentalQuery.setMaxResults(anyInt())).thenReturn(rentalQuery);
        when(rentalQuery.getResultList()).thenReturn(List.of(rental));
        when(snapshots.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());

        var checkIns = (TypedQuery<CheckIn>) mock(TypedQuery.class);
        when(em.createQuery(contains("from CheckIn c"), eq(CheckIn.class))).thenReturn(checkIns);
        when(checkIns.setParameter(anyString(), any())).thenReturn(checkIns);
        when(checkIns.setMaxResults(anyInt())).thenReturn(checkIns);
        when(checkIns.getResultList()).thenReturn(List.of());

        var notice = new Notification();
        ReflectionTestUtils.setField(notice, "id", UUID.randomUUID());
        notice.setUser(customer);
        notice.setRelatedEntityId(reservation.getId());
        notice.setType(NotificationType.CHECKIN);
        notice.setTitle("Nhắc đặt lại lịch kiểm kê");
        notice.setContent("Bạn đã bỏ lỡ lịch kiểm kê trước đó. Vui lòng đặt lại lịch mới trước hạn 22:26 30/11/2026 (giờ Việt Nam).");

        service.notification(notice);

        var payload = ArgumentCaptor.forClass(Map.class);
        verify(mail).queueTemplate(eq(customer.getId()), eq(customer.getEmail()),
            eq("StorageHub - Nhắc đặt lại lịch kiểm kê"), eq("customer-lifecycle"), payload.capture(),
            eq("CUSTOMER_TRANSACTIONAL_EMAIL_SENT"), eq("notification:" + notice.getId()));
        var details = (Map<String, String>) payload.getValue().get("details");
        assertThat(details)
            .containsKey("Thời điểm nhận kho")
            .containsKey("Lịch hẹn kiểm kê trước đó (đã bỏ lỡ)")
            .containsKey("Trạng thái lịch kiểm kê")
            .containsKey("Giấy tờ khi kiểm kê")
            .containsEntry("Ngày bắt đầu thuê", "24/11/2026")
            .doesNotContainKeys("Lịch nhận kho", "Giấy tờ khi nhận kho");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mismatchReturnEmailOmitsCheckInAnd72hInventoryFieldsAndShowsReturnSchedule() {
        var em = mock(EntityManager.class);
        var mail = mock(TransactionalEmailService.class);
        var properties = new EmailProperties();
        properties.setFrontendBaseUrl("http://localhost:8443");
        var snapshots = mock(ReservationPricingSnapshotRepository.class);
        var service = new CustomerLifecycleEmailService(em, mail, properties,
            mock(RentalPeriodResolver.class), snapshots);
        var codes = mock(BusinessRecordCodes.class);
        when(codes.noticeText(anyString())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(service, "displayCodes", codes);

        var customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        customer.setEmail("customer@example.test");
        customer.setFullName("Thanh Thao");
        var role = new Role();
        role.setCode(RoleCode.CUSTOMER);
        customer.getRoles().add(role);

        var reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setCustomer(customer);
        reservation.setReservationCode("RSV-TEST-16");
        reservation.setAppointmentAt(Instant.parse("2026-11-23T14:56:00Z"));

        var rental = new Rental();
        ReflectionTestUtils.setField(rental, "id", UUID.randomUUID());
        rental.setCustomer(customer);
        rental.setReservation(reservation);
        rental.setStartDate(LocalDate.of(2026, 11, 24));
        rental.setMoveInCompletedAt(Instant.parse("2026-11-23T15:26:00Z"));
        rental.setInspectionCompletionDeadlineAt(Instant.now().plus(72, ChronoUnit.HOURS));
        rental.setInventoryRemovalDeadlineAt(Instant.parse("2026-11-30T16:59:59Z"));
        rental.setInventoryReturnExtensionCount(1);

        var rc = new ReturnCase();
        ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
        rc.setRental(rental);
        rc.setCustomer(customer);
        rc.setReturnType(ReturnType.INVENTORY_MISMATCH);
        rc.setScheduledAppointmentAt(Instant.parse("2026-11-28T03:00:00Z"));
        rc.setReturnExtensionCount(1);
        when(em.find(ReturnCase.class, rc.getId())).thenReturn(rc);
        when(snapshots.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());

        var notice = new Notification();
        ReflectionTestUtils.setField(notice, "id", UUID.randomUUID());
        notice.setUser(customer);
        notice.setRelatedEntityId(rc.getId());
        notice.setType(NotificationType.RETURN);
        notice.setTitle("Đã cập nhật lịch hẹn trả kho");
        notice.setContent("Lịch hẹn trả kho đã được cập nhật.");

        service.notification(notice);

        var payload = ArgumentCaptor.forClass(Map.class);
        verify(mail).queueTemplate(eq(customer.getId()), eq(customer.getEmail()),
            eq("StorageHub - Đã cập nhật lịch hẹn trả kho"), eq("customer-lifecycle"), payload.capture(),
            eq("CUSTOMER_TRANSACTIONAL_EMAIL_SENT"), eq("notification:" + notice.getId()));
        var details = (Map<String, String>) payload.getValue().get("details");
        assertThat(details)
            .containsEntry("Hình thức trả kho", "Trả do kiểm kê sai lệch")
            .containsEntry("Số lượt gia hạn dọn hàng", "Đã dùng 1/2 lần")
            .containsKey("Hạn cuối dọn hàng")
            .containsKey("Lịch hẹn bàn giao trả kho")
            .containsKey("Giấy tờ khi trả kho")
            .doesNotContainKeys("Lịch nhận kho", "Giấy tờ khi nhận kho", "Hạn hoàn tất kiểm kê (72 giờ)");
    }
}
