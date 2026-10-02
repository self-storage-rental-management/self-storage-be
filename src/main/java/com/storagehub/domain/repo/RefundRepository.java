package com.storagehub.domain.repo;

import com.storagehub.domain.model.Refund;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
}
