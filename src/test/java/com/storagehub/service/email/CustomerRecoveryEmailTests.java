package com.storagehub.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.storagehub.config.EmailProperties;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.service.BusinessRecordCodes;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
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
    }
}
