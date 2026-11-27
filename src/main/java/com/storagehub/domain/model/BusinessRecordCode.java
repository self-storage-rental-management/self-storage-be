package com.storagehub.domain.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "business_record_codes")
public class BusinessRecordCode {

    @Id
    @Column(name = "resource_key", length = 120, nullable = false)
    private String resourceKey;

    @Column(name = "type_code", length = 16, nullable = false)
    private String typeCode;

    @Column(name = "period", length = 8, nullable = false)
    private String period;

    @Column(name = "display_code", length = 16, nullable = false, unique = true)
    private String displayCode;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    public BusinessRecordCode() {}

    public BusinessRecordCode(String resourceKey, String typeCode, String period, String displayCode, Instant recordedAt) {
        this.resourceKey = resourceKey;
        this.typeCode = typeCode;
        this.period = period;
        this.displayCode = displayCode;
        this.recordedAt = recordedAt;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getDisplayCode() {
        return displayCode;
    }

    public void setDisplayCode(String displayCode) {
        this.displayCode = displayCode;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }
}
