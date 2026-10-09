package com.storagehub.service.rental.period;

import com.storagehub.domain.model.Rental;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Additive metadata in existing JSON, never a migration or synthetic historical proof. */
public final class RentalPeriodEvidence {
    private RentalPeriodEvidence() {}
    public static Map<String, Object> receipt(Rental rental, boolean created) {
        var data = identity(rental);
        data.put("schema", "CUSTOMER_RECEIPT_PERIOD_V1");
        data.put("rentalCreated", created);
        data.put("startDate", date(rental.getStartDate()));
        data.put("storedEndDate", date(rental.getContractEndDate()));
        data.put("convention", created ? "EXCLUSIVE" : "UNKNOWN");
        return data;
    }
    public static Map<String, Object> completion(Rental rental, RentalPeriod old, LocalDate newStoredEnd,
        java.util.UUID acceptedQuoteId) {
        var data = identity(rental);
        data.put("schema", "RENEWAL_COMPLETION_PERIOD_V1");
        data.put("startDate", date(old.startDate()));
        data.put("oldStoredEndDate", date(old.storedEndDate()));
        data.put("storedEndDate", date(newStoredEnd));
        data.put("convention", old.convention().name());
        data.put("previousProof", old.reference());
        data.put("acceptedQuoteId", acceptedQuoteId);
        return data;
    }
    private static String date(LocalDate value) { return value == null ? null : value.toString(); }
    private static LinkedHashMap<String, Object> identity(Rental rental) {
        var data = new LinkedHashMap<String, Object>();
        data.put("rentalId", rental.getId());
        data.put("reservationId", rental.getReservation() == null ? null : rental.getReservation().getId());
        data.put("customerId", rental.getCustomer() == null ? null : rental.getCustomer().getId());
        data.put("facilityId", rental.getFacility() == null ? null : rental.getFacility().getId());
        data.put("storageUnitId", rental.getStorageUnit() == null ? null : rental.getStorageUnit().getId());
        return data;
    }
}
