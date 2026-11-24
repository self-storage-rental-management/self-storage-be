package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Records the at-site inventory confirmation: who recorded the arrangement with the verified
 * customer, when, the evidence, and the version of the inspection record that was approved.
 * <p>
 * This lives in its own table because {@code check_ins} already reaches MySQL's 65.535-byte
 * row-size limit, so further columns cannot be added to it. A missing row means no at-site
 * confirmation was ever recorded; legacy records are never backfilled.
 */
@Entity
@Table(name = "check_in_inventory_confirmations")
@Getter
@Setter
@NoArgsConstructor
public class CheckInInventoryConfirmation {

    @Id
    @Column(name = "check_in_id", columnDefinition = "BINARY(16)")
    private java.util.UUID checkInId;

    @Column(name = "confirmed_by", columnDefinition = "BINARY(16)")
    private java.util.UUID confirmedBy;

    @Column(name = "confirmed_by_name", columnDefinition = "TEXT")
    private String confirmedByName;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "evidence_json", columnDefinition = "LONGTEXT")
    private String evidenceJson;

    @Column(name = "result_version", nullable = false)
    private long resultVersion;
}
