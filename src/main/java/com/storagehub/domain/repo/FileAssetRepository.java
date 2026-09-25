package com.storagehub.domain.repo;

import com.storagehub.domain.model.FileAsset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileAssetRepository extends JpaRepository<FileAsset, UUID> {
}
