package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "file_assets")
@Getter
@Setter
@NoArgsConstructor
public class FileAsset extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Column(nullable = false, length = 500)
    private String storageKey;

    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String checksumSha256;

    @Column(length = 64)
    private String entityType;

    @Column
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FileAssetStatus status = FileAssetStatus.ACTIVE;
}
