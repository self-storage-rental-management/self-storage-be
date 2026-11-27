package com.storagehub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.reservation.ReservationResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.text.TextEncodingRepair;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reads customer-visible reservation data only from immutable snapshots. */
public final class ReservationSnapshotView {
    public static final String MISSING = "Thiếu dữ liệu lịch sử, cần đối soát";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReservationSnapshotView() {}

    public static String unitTypeName(Reservation reservation) {
        return unitTypeName(reservation, null);
    }

    public static String unitTypeName(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return text(unit(reservation, snapshot), "name", MISSING);
    }

    public static String facilityName(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return text(facility(reservation, snapshot), "name", MISSING);
    }

    public static String facilityAddress(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return text(facility(reservation, snapshot), "address", MISSING);
    }

    public static String facilityCode(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return text(facility(reservation, snapshot), "code", MISSING);
    }

    public static String assignedUnitCode(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return text(assignedUnit(reservation, snapshot), "code", null);
    }

    public static BigDecimal unitTypeDecimal(Reservation reservation, String field) {
        return unitTypeDecimal(reservation, null, field);
    }

    public static BigDecimal unitTypeDecimal(Reservation reservation, ReservationPricingSnapshot snapshot, String field) {
        if (!Set.of(
            "lengthM", "widthM", "heightM", "maxLoadKg", "maxStoredLoadKg",
            "rackLengthM", "rackWidthM", "rackHeightM", "aisleWidthM", "storageAisleWidthM",
            "gateWidthM", "gateHeightM", "lowestObstructionHeightM", "overheadClearanceM"
        ).contains(field)) {
            throw new IllegalArgumentException("Unsupported unit type snapshot field: " + field);
        }
        JsonNode value = unit(reservation, snapshot);
        if (value != null) value = value.path(field);
        if (value != null && (value.isNumber() || value.isTextual())) {
            try {
                BigDecimal number = new BigDecimal(value.asText());
                if (number.signum() >= 0 && number.precision() <= 18 && Math.abs(number.scale()) <= 8) {
                    return number;
                }
            } catch (NumberFormatException ignored) {
                // Do not read the live catalog when the historical value is invalid.
            }
        }
        throw ApiExceptions.conflict(MISSING + ": thông số kho " + field);
    }

    static int unitTypeRackCount(Reservation reservation) {
        return unitTypeRackCount(reservation, null);
    }

    static int unitTypeRackCount(Reservation reservation, ReservationPricingSnapshot snapshot) {
        JsonNode value = unit(reservation, snapshot);
        value = value == null ? null : value.path("rackCount");
        if (value != null && (value.isNumber() || value.isTextual())) {
            try {
                int count = new BigDecimal(value.asText()).intValueExact();
                if (count >= 0) return count;
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Missing or invalid historical count.
            }
        }
        throw ApiExceptions.conflict(MISSING + ": số khung kệ");
    }

    static boolean pricingComplete(Reservation reservation, ReservationPricingSnapshot snapshot) {
        var quote = reservation.getSourceQuote();
        return snapshot != null && quote != null && quote.getId() != null
            && snapshot.getReservation() != null && Objects.equals(snapshot.getReservation().getId(), reservation.getId())
            && snapshot.getPricingPolicyVersion() != null && !snapshot.getPricingPolicyVersion().isBlank()
            && snapshot.getPricingPolicyVersion().equals(quote.getPolicyVersion())
            && snapshot.getPricingPackageCode() != null && snapshot.getPricingPackageCode().equals(quote.getPricingPackageCode())
            && snapshot.getRentalMonths() > 0 && snapshot.getRentalMonths() == quote.getRentalMonths()
            && snapshot.getFreeSetupDays() >= 0 && snapshot.getFreeSetupDays() == quote.getFreeSetupDays()
            && snapshot.getFreeSetupDays() == reservation.getSetupDaysSnapshot()
            && snapshot.getQuotedAt() != null && snapshot.getQuotedAt().equals(quote.getQuotedAt())
            && same(snapshot.getMonthlyPrice(), quote.getMonthlyPrice())
            && same(snapshot.getGrossRentalAmount(), quote.getSubtotal())
            && Objects.equals(snapshot.getSetupEarlyExitPolicyVersion(), quote.getSetupEarlyExitPolicyVersion())
            && (snapshot.getSetupEarlyExitPolicyVersion() == null || same(snapshot.getSetupEarlyExitFee(), quote.getSetupEarlyExitFee()))
            && same(snapshot.getDiscountRate(), quote.getDiscountRate())
            && same(snapshot.getDiscountAmount(), quote.getDiscountAmount())
            && same(snapshot.getNetRentalAmount(), quote.getTotalAfterDiscount())
            && same(snapshot.getReservationDepositAmount(), quote.getReservationDepositAmount())
            && same(snapshot.getSecurityDepositAmount(), quote.getSecurityDepositAmount())
            && same(snapshot.getRemainingRentalAmount(), quote.getRemainingRentalAmount())
            && same(snapshot.getDueAtCheckIn(), quote.getDueAtCheckIn())
            && same(snapshot.getTotalInitialObligation(), quote.getTotalInitialObligation());
    }

    static ReservationResponse missingResponse(Reservation reservation) {
        var response = new ReservationResponse(
            reservation.getId(), reservation.getReservationCode(),
            reservation.getSourceQuote() == null ? null : reservation.getSourceQuote().getId(),
            reservation.getFacility() == null ? null : reservation.getFacility().getId(),
            reservation.getUnitType() == null ? null : reservation.getUnitType().getId(),
            reservation.getStatus(), reservation.getGoodsReviewStatus(), reservation.getCompatibilityResult(),
            reservation.getStartDate(), reservation.getEndDate(), null, null, null, null, null, null,
            reservation.getTotalGoodsVolumeM3(), reservation.getTotalGoodsWeightKg(),
            reservation.getHoldExpiresAt(), reservation.getCreatedAt()
        );
        response.setCustomerName(reservation.getCustomer() == null ? null : reservation.getCustomer().getFullName());
        response.setCustomerEmail(reservation.getCustomer() == null ? null : reservation.getCustomer().getEmail());
        response.setAppointmentAt(reservation.getAppointmentAt());
        describe(response, reservation, null);
        return response;
    }

    static int requireSetupDays(Reservation reservation, ReservationPricingSnapshot snapshot) {
        if (!pricingComplete(reservation, snapshot)) {
            throw ApiExceptions.conflict(MISSING + ": giá, chính sách hoặc số ngày chuẩn bị đã chấp nhận");
        }
        return snapshot.getFreeSetupDays();
    }

    static void describe(
        ReservationResponse response,
        Reservation reservation,
        ReservationPricingSnapshot snapshot
    ) {
        var warnings = new ArrayList<String>();
        response.setUnitTypeName(unitTypeName(reservation, snapshot));
        boolean pricing = pricingComplete(reservation, snapshot);
        response.setPricingSnapshotStatus(pricing ? "COMPLETE" : "INCOMPLETE");
        response.setPricingPolicyVersion(snapshot == null ? null : snapshot.getPricingPolicyVersion());
        response.setPricingQuotedAt(snapshot == null ? null : snapshot.getQuotedAt());
        response.setPricingExpiresAt(snapshot == null ? null : snapshot.getExpiresAt());
        response.setFreeSetupDays(pricing ? snapshot.getFreeSetupDays() : null);
        response.setSetupEarlyExitPolicyVersion(pricing ? snapshot.getSetupEarlyExitPolicyVersion() : null);
        response.setSetupEarlyExitFee(pricing ? snapshot.getSetupEarlyExitFee() : null);
        if (!pricing) warnings.add(MISSING + ": giá, chính sách hoặc số ngày chuẩn bị");

        JsonNode facility = facility(reservation, snapshot);
        if (facility == null || !facility.hasNonNull("name") || !facility.hasNonNull("address")) {
            warnings.add(MISSING + ": tên hoặc địa chỉ cơ sở");
        } else {
            response.setFacilitySnapshot(new ReservationResponse.FacilitySnapshot(
                text(facility, "snapshotVersion", null), instant(facility, "capturedAt"),
                text(facility, "code", null), text(facility, "name", null), text(facility, "address", null),
                text(facility, "city", null), text(facility, "phone", null), "24/7",
                facility.has("climateControlled") && !facility.get("climateControlled").isNull()
                    ? facility.get("climateControlled").asBoolean() : null,
                text(facility, "securityDescription", null)
            ));
        }

        JsonNode unit = unit(reservation, snapshot);
        try {
            if (unit == null || !unit.hasNonNull("name")) throw ApiExceptions.conflict(MISSING);
            BigDecimal length = decimal(unit, "lengthM");
            BigDecimal width = decimal(unit, "widthM");
            BigDecimal height = decimal(unit, "heightM");
            response.setUnitTypeSnapshot(new ReservationResponse.UnitTypeSnapshot(
                text(unit, "name", MISSING), length, width, height,
                length.multiply(width), length.multiply(width).multiply(height),
                decimal(unit, "maxLoadKg"), integer(unit, "rackCount"),
                decimal(unit, "rackLengthM"), decimal(unit, "rackWidthM"), decimal(unit, "rackHeightM")
            ));
            response.setStorageConfigurationSnapshot(new ReservationResponse.StorageConfigurationSnapshot(
                text(unit, "snapshotVersion", null), instant(unit, "capturedAt"),
                decimalOrNull(unit, "maxStoredLoadKg"), decimalOrNull(unit, "aisleWidthM"),
                decimalOrNull(unit, "storageAisleWidthM"), decimalOrNull(unit, "gateWidthM"),
                decimalOrNull(unit, "gateHeightM"), decimalOrNull(unit, "lowestObstructionHeightM"),
                decimalOrNull(unit, "overheadClearanceM"), text(unit, "rackLayoutJson", null),
                text(unit, "palletRatingCatalogJson", null)
            ));
        } catch (com.storagehub.common.api.ApiException missing) {
            response.setUnitTypeSnapshot(null);
            response.setStorageConfigurationSnapshot(null);
            warnings.add(MISSING + ": tên hoặc thông số loại kho");
        }

        JsonNode assigned = assignedUnit(reservation, snapshot);
        if (assigned != null && assigned.hasNonNull("code")) {
            response.setAssignedUnitSnapshot(new ReservationResponse.StorageUnitSnapshot(
                uuid(assigned, "id"), text(assigned, "code", null), text(assigned, "floor", null),
                text(assigned, "zone", null), text(assigned, "status", null),
                text(assigned, "rackLayoutJson", null), text(assigned, "unitTypeCode", null),
                text(assigned, "unitTypeName", null), text(assigned, "snapshotVersion", null),
                instant(assigned, "capturedAt")
            ));
            response.setAssignedUnitId(uuid(assigned, "id"));
            response.setAssignedUnitCode(text(assigned, "code", null));
            response.setAssignedUnitStatus(text(assigned, "status", null));
        }

        response.setSnapshotWarnings(List.copyOf(warnings));
    }

    private static JsonNode unit(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return firstObject(
            snapshot == null ? null : snapshot.getUnitTypeSnapshotJson(),
            reservation.getSourceQuote() == null ? null : reservation.getSourceQuote().getUnitTypeSnapshotJson()
        );
    }

    private static JsonNode facility(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return firstObject(
            snapshot == null ? null : snapshot.getFacilitySnapshotJson(),
            reservation.getSourceQuote() == null ? null : reservation.getSourceQuote().getFacilitySnapshotJson()
        );
    }

    private static JsonNode assignedUnit(Reservation reservation, ReservationPricingSnapshot snapshot) {
        return firstObject(snapshot == null ? null : snapshot.getAssignedUnitSnapshotJson(), null);
    }

    private static JsonNode firstObject(String primary, String secondary) {
        JsonNode first = readObject(primary);
        if (first != null && first.size() > 0) return first;
        return readObject(secondary);
    }

    private static JsonNode readObject(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(value);
            return node != null && node.isObject() && node.size() > 0 ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
            ? TextEncodingRepair.repairUtf8Mojibake(value.asText())
            : fallback;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        BigDecimal value = decimalOrNull(node, field);
        if (value == null) throw ApiExceptions.conflict(MISSING + ": thông số kho " + field);
        return value;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (!value.isNumber() && !value.isTextual())) return null;
        try {
            BigDecimal number = new BigDecimal(value.asText());
            return number.signum() >= 0 && number.precision() <= 18 && Math.abs(number.scale()) <= 8 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int integer(JsonNode node, String field) {
        if (node == null) throw ApiExceptions.conflict(MISSING + ": thông số kho " + field);
        JsonNode value = node.get(field);
        try {
            int number = new BigDecimal(value.asText()).intValueExact();
            if (number >= 0) return number;
        } catch (Exception ignored) {
            // handled below
        }
        throw ApiExceptions.conflict(MISSING + ": thông số kho " + field);
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field, null);
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field, null);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.signum() >= 0 && left.compareTo(right) == 0;
    }
}
