package com.storagehub.domain.repo;

import com.storagehub.domain.model.CheckInInventoryConfirmation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInInventoryConfirmationRepository
    extends JpaRepository<CheckInInventoryConfirmation, UUID> {
}
