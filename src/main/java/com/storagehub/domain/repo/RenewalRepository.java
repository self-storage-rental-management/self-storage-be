package com.storagehub.domain.repo;

import com.storagehub.domain.model.Renewal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RenewalRepository extends JpaRepository<Renewal, UUID>, JpaSpecificationExecutor<Renewal> {}
