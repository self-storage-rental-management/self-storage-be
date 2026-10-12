package com.storagehub.domain.repo;

import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.FileAssetStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileAssetRepository extends JpaRepository<FileAsset, UUID> {
    List<FileAsset> findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, UUID entityId);
    long countByEntityTypeAndEntityIdAndStatus(String entityType, UUID entityId, FileAssetStatus status);
}
