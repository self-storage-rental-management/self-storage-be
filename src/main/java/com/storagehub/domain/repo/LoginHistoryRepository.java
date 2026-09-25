package com.storagehub.domain.repo;

import com.storagehub.domain.model.LoginHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, UUID> {
}
