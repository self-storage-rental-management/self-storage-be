package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only publications. Revision zero is the imported pre-publication baseline. */
@Entity
@Table(name = "return_fee_schedule_versions")
@Getter
@NoArgsConstructor
public class ReturnFeeScheduleVersion {
    @Id
    private Long revision;

    @Column(name = "published_at", updatable = false)
    private java.time.Instant publishedAt;

    @Column(columnDefinition = "LONGTEXT", updatable = false)
    private String payload;

    @Column(name = "published_by", length = 36, updatable = false)
    private String publishedBy;

    public ReturnFeeScheduleVersion(long revision, java.time.Instant publishedAt, String payload, String publishedBy) {
        this.revision = revision;
        this.publishedAt = publishedAt;
        this.payload = payload;
        this.publishedBy = publishedBy;
    }
}
