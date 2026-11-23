package com.storagehub.domain.repo;

import com.storagehub.domain.model.Renewal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RenewalRepository extends JpaRepository<Renewal, UUID>, JpaSpecificationExecutor<Renewal> {
    @Query("select max(r.displaySequence) from Renewal r where r.rental.id = :rentalId")
    Integer findMaxDisplaySequence(@Param("rentalId") UUID rentalId);
}
