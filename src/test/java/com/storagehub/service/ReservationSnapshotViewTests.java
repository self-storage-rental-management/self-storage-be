package com.storagehub.service;

import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.UnitType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationSnapshotViewTests {
    @Test
    void oldReservationKeepsQuotedUnitTypeNameAfterCatalogEdit() {
        UnitType currentType = new UnitType();
        currentType.setName("Tên loại kho mới");
        ReservationQuote quote = new ReservationQuote();
        quote.setUnitTypeSnapshotJson("{\"code\":\"M\",\"name\":\"Tên loại kho tại lúc báo giá\"}");
        Reservation reservation = new Reservation();
        reservation.setUnitType(currentType);
        reservation.setSourceQuote(quote);

        assertThat(ReservationSnapshotView.unitTypeName(reservation))
            .isEqualTo("Tên loại kho tại lúc báo giá");
    }

    @Test
    void legacyReservationRequiresReconciliationInsteadOfUsingTheCurrentCatalogName() {
        UnitType currentType = new UnitType();
        currentType.setName("Tên loại kho hiện tại");
        ReservationQuote quote = new ReservationQuote();
        quote.setUnitTypeSnapshotJson("{}");
        Reservation reservation = new Reservation();
        reservation.setUnitType(currentType);
        reservation.setSourceQuote(quote);

        assertThat(ReservationSnapshotView.unitTypeName(reservation))
            .isEqualTo(ReservationSnapshotView.MISSING)
            .isNotEqualTo("Tên loại kho hiện tại");
    }
}
