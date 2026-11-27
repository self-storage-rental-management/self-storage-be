package com.storagehub.service;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.User;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReservationCustomerSnapshotTests {
    @Test void profileEditsDoNotChangeBookingCustomerDetails() {
        var user = new User();
        user.setFullName("Tên lúc đặt"); user.setEmail("original@example.com");
        user.setPhone("0901111111"); user.setPermanentAddress("Địa chỉ lúc đặt");
        var reservation = new Reservation(); reservation.setCustomer(user);
        reservation.setIdentityDocumentNumber("079203009988");
        reservation.captureCustomerDetails();

        user.setFullName("Tên mới"); user.setEmail("new@example.com");
        user.setPhone("0902222222"); user.setPermanentAddress("Địa chỉ mới");

        assertThat(reservation.getCustomerName()).isEqualTo("Tên lúc đặt");
        assertThat(reservation.getCustomerEmail()).isEqualTo("original@example.com");
        assertThat(reservation.getCustomerPhone()).isEqualTo("0901111111");
        assertThat(reservation.getCustomerAddress()).isEqualTo("Địa chỉ lúc đặt");
        assertThat(reservation.getIdentityDocumentNumber()).isEqualTo("079203009988");
        var response = ReservationSnapshotView.missingResponse(reservation);
        assertThat(response.getCustomerName()).isEqualTo("Tên lúc đặt");
        assertThat(response.getCustomerEmail()).isEqualTo("original@example.com");
        assertThat(response.getCustomerPhone()).isEqualTo("0901111111");
        assertThat(response.getCustomerAddress()).isEqualTo("Địa chỉ lúc đặt");
    }

    @Test void explicitlyDeclaredBookingContactsTakePrecedenceOverProfile() {
        var user = new User(); user.setPhone("0901111111"); user.setPermanentAddress("Hồ sơ");
        var reservation = new Reservation(); reservation.setCustomer(user);
        reservation.setCustomerPhone("0903333333"); reservation.setCustomerAddress("Trên đơn");
        reservation.captureCustomerDetails();
        assertThat(reservation.getCustomerPhone()).isEqualTo("0903333333");
        assertThat(reservation.getCustomerAddress()).isEqualTo("Trên đơn");
    }

    @Test void missingBookingContactsDoNotFollowLaterProfileChanges() {
        var user = new User();
        var reservation = new Reservation(); reservation.setCustomer(user);
        reservation.captureCustomerDetails();
        user.setPhone("0902222222"); user.setPermanentAddress("Bổ sung sau");
        assertThat(reservation.getCustomerPhone()).isNull();
        assertThat(reservation.getCustomerAddress()).isNull();
        var response = ReservationSnapshotView.missingResponse(reservation);
        assertThat(response.getCustomerPhone()).isNull();
        assertThat(response.getCustomerAddress()).isNull();
    }
}
