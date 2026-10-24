package com.storagehub.testing;

import com.storagehub.domain.model.ReservationQuote;

/** Historical quotes use the same empty-snapshot markers as the database migration.
 * These fixtures do not claim that legacy rows have verified goods or technical data.
 * Tests of new quotes still exercise ReservationQuoteService's actual snapshots.
 */
public final class LegacyReservationQuoteFixture {
    private LegacyReservationQuoteFixture() {}

    public static ReservationQuote create() {
        ReservationQuote quote = new ReservationQuote();
        quote.setGoodsSnapshotJson("[]");
        quote.setUnitTypeSnapshotJson("{}");
        return quote;
    }
}
