package com.storagehub.domain.repo;

import com.storagehub.domain.model.SystemSetting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, UUID> {
    Optional<SystemSetting> findBySettingKey(String settingKey);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from SystemSetting s where s.settingKey = :key")
    Optional<SystemSetting> findBySettingKeyForUpdate(@org.springframework.data.repository.query.Param("key") String key);
}
