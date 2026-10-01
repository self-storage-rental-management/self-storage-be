package com.storagehub.api.reservation;

import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class UnitAssignmentResponse {

    private UUID reservationId;
    private String reservationCode;
    private ReservationStatus reservationStatus;
    private UUID customerId;
    private String customerEmail;
    private UUID facilityId;
    private UUID unitTypeId;
    private UUID storageUnitId;
    private String storageUnitCode;
    private StorageUnitStatus storageUnitStatus;
    private LocalDate startDate;
    private LocalDate endDate;
    private Instant assignedAt;

    public UnitAssignmentResponse() {
    }

    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public String getReservationCode() { return reservationCode; }
    public void setReservationCode(String reservationCode) { this.reservationCode = reservationCode; }
    public ReservationStatus getReservationStatus() { return reservationStatus; }
    public void setReservationStatus(ReservationStatus reservationStatus) { this.reservationStatus = reservationStatus; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public UUID getUnitTypeId() { return unitTypeId; }
    public void setUnitTypeId(UUID unitTypeId) { this.unitTypeId = unitTypeId; }
    public UUID getStorageUnitId() { return storageUnitId; }
    public void setStorageUnitId(UUID storageUnitId) { this.storageUnitId = storageUnitId; }
    public String getStorageUnitCode() { return storageUnitCode; }
    public void setStorageUnitCode(String storageUnitCode) { this.storageUnitCode = storageUnitCode; }
    public StorageUnitStatus getStorageUnitStatus() { return storageUnitStatus; }
    public void setStorageUnitStatus(StorageUnitStatus storageUnitStatus) { this.storageUnitStatus = storageUnitStatus; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
}
