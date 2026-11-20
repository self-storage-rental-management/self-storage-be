package com.storagehub.service;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.UnitType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds immutable, customer-visible snapshots for a reservation lifecycle. */
final class ReservationSnapshotCodec {
    static final String VERSION = "RESERVATION_STORAGE_SNAPSHOT_V1";

    private ReservationSnapshotCodec() {}

    static String facility(Facility facility, Instant capturedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotVersion", VERSION);
        snapshot.put("capturedAt", instant(capturedAt));
        snapshot.put("id", id(facility == null ? null : facility.getId()));
        snapshot.put("code", facility == null ? null : facility.getCode());
        snapshot.put("name", facility == null ? null : facility.getName());
        snapshot.put("address", facility == null ? null : facility.getAddress());
        snapshot.put("city", facility == null ? null : facility.getCity());
        snapshot.put("phone", facility == null ? null : facility.getPhone());
        snapshot.put("accessHours", facility == null ? null : facility.getAccessHours());
        snapshot.put("climateControlled", facility != null && facility.isClimateControlled());
        snapshot.put("securityDescription", facility == null ? null : facility.getSecurityDescription());
        snapshot.put("status", facility == null || facility.getStatus() == null ? null : facility.getStatus().name());
        return GoodsSnapshotCodec.encodeObject(snapshot);
    }

    static String unitType(UnitType unitType, Instant capturedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotVersion", VERSION);
        snapshot.put("capturedAt", instant(capturedAt));
        snapshot.put("id", id(unitType == null ? null : unitType.getId()));
        snapshot.put("facilityId", unitType == null || unitType.getFacility() == null ? null : id(unitType.getFacility().getId()));
        snapshot.put("code", unitType == null ? null : unitType.getCode());
        snapshot.put("name", unitType == null ? null : unitType.getName());
        snapshot.put("description", unitType == null ? null : unitType.getDescription());
        snapshot.put("lengthM", unitType == null ? null : unitType.getLengthM());
        snapshot.put("widthM", unitType == null ? null : unitType.getWidthM());
        snapshot.put("heightM", unitType == null ? null : unitType.getHeightM());
        snapshot.put("monthlyPrice", unitType == null ? null : unitType.getMonthlyPrice());
        snapshot.put("securityDepositAmount", unitType == null ? null : unitType.getSecurityDepositAmount());
        snapshot.put("maxLoadKg", unitType == null ? null : unitType.getMaxLoadKg());
        snapshot.put("maxStoredLoadKg", unitType == null ? null : unitType.getMaxStoredLoadKg());
        snapshot.put("rackCount", unitType == null ? null : unitType.getRackCount());
        snapshot.put("rackLengthM", unitType == null ? null : unitType.getRackLengthM());
        snapshot.put("rackWidthM", unitType == null ? null : unitType.getRackWidthM());
        snapshot.put("rackHeightM", unitType == null ? null : unitType.getRackHeightM());
        snapshot.put("aisleWidthM", unitType == null ? null : unitType.getAisleWidthM());
        snapshot.put("storageAisleWidthM", unitType == null ? null : unitType.getStorageAisleWidthM());
        snapshot.put("gateWidthM", unitType == null ? null : unitType.getGateWidthM());
        snapshot.put("gateHeightM", unitType == null ? null : unitType.getGateHeightM());
        snapshot.put("lowestObstructionHeightM", unitType == null ? null : unitType.getLowestObstructionHeightM());
        snapshot.put("overheadClearanceM", unitType == null ? null : unitType.getOverheadClearanceM());
        snapshot.put("trolleyType", unitType == null ? null : unitType.getTrolleyType());
        snapshot.put("smallBoxCapacity", unitType == null ? null : unitType.getSmallBoxCapacity());
        snapshot.put("largeBoxCapacity", unitType == null ? null : unitType.getLargeBoxCapacity());
        snapshot.put("rackLayoutJson", unitType == null ? null : unitType.getRackLayoutJson());
        snapshot.put("palletRatingCatalogJson", unitType == null ? null : unitType.getPalletRatingCatalogJson());
        snapshot.put("publishedRevision", unitType == null ? null : unitType.getPublishedRevision());
        snapshot.put("acceptedGoodsJson", unitType == null ? null : unitType.getAcceptedGoodsJson());
        snapshot.put("status", unitType == null || unitType.getStatus() == null ? null : unitType.getStatus().name());
        return GoodsSnapshotCodec.encodeObject(snapshot);
    }

    static String storageUnit(StorageUnit unit, Instant capturedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotVersion", VERSION);
        snapshot.put("capturedAt", instant(capturedAt));
        snapshot.put("id", id(unit == null ? null : unit.getId()));
        snapshot.put("code", unit == null ? null : unit.getCode());
        snapshot.put("floor", unit == null ? null : unit.getFloor());
        snapshot.put("zone", unit == null ? null : unit.getZone());
        snapshot.put("status", unit == null || unit.getStatus() == null ? null : unit.getStatus().name());
        snapshot.put("rackLayoutJson", unit == null ? null : unit.getRackLayoutJson());
        snapshot.put("unitTypeId", unit == null || unit.getUnitType() == null ? null : id(unit.getUnitType().getId()));
        snapshot.put("unitTypeCode", unit == null || unit.getUnitType() == null ? null : unit.getUnitType().getCode());
        snapshot.put("unitTypeName", unit == null || unit.getUnitType() == null ? null : unit.getUnitType().getName());
        return GoodsSnapshotCodec.encodeObject(snapshot);
    }

    static String hash(String... snapshots) {
        try {
            StringBuilder canonical = new StringBuilder();
            for (String snapshot : snapshots) {
                String value = snapshot == null ? "<null>" : snapshot;
                canonical.append(value.length()).append(':').append(value).append('|');
            }
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
