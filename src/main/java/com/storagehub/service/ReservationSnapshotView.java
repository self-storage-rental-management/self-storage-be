package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.Reservation;

/** Reads customer-visible values from the immutable quote captured by a reservation. */
final class ReservationSnapshotView {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReservationSnapshotView() {}

    static String unitTypeName(Reservation reservation) {
        try {
            String snapshot = reservation.getSourceQuote().getUnitTypeSnapshotJson();
            String name = JSON.readTree(snapshot).path("name").asText(null);
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ignored) {
            // Legacy rows can have an empty snapshot; retain the historical fallback.
        }
        return reservation.getUnitType().getName();
    }

    static java.math.BigDecimal unitTypeDecimal(Reservation reservation, String field) {
        try {
            var value = JSON.readTree(reservation.getSourceQuote().getUnitTypeSnapshotJson()).path(field);
            if (value.isNumber() || value.isTextual()) return new java.math.BigDecimal(value.asText());
        } catch (Exception ignored) {
            // Fall through to the legacy relation for quotes created before snapshots.
        }
        var type = reservation.getUnitType();
        return switch (field) {
            case "lengthM" -> type.getLengthM();
            case "widthM" -> type.getWidthM();
            case "heightM" -> type.getHeightM();
            case "maxLoadKg" -> type.getMaxLoadKg();
            case "rackLengthM" -> type.getRackLengthM();
            case "rackWidthM" -> type.getRackWidthM();
            case "rackHeightM" -> type.getRackHeightM();
            default -> throw new IllegalArgumentException("Unsupported unit type snapshot field: " + field);
        };
    }

    static int unitTypeRackCount(Reservation reservation) {
        try {
            var value = JSON.readTree(reservation.getSourceQuote().getUnitTypeSnapshotJson()).path("rackCount");
            if (value.canConvertToInt()) return value.asInt();
        } catch (Exception ignored) {
            // Fall through to the legacy relation.
        }
        return reservation.getUnitType().getRackCount();
    }
}
