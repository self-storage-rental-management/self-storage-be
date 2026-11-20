package com.storagehub.domain.repo;

import com.storagehub.domain.model.Contract;
import com.storagehub.domain.model.ContractStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByRental_Id(UUID rentalId);

    Optional<Contract> findByContractNumber(String contractNumber);

    List<Contract> findByStatus(ContractStatus status);

    @Query("""
        select count(c) from Contract c
        where c.rental.facility.id = :facilityId
          and c.status = com.storagehub.domain.model.ContractStatus.SIGNED
          and c.rental.status = com.storagehub.domain.model.RentalStatus.active
        """)
    long countActiveByFacilityId(@Param("facilityId") UUID facilityId);

    @Query("select c from Contract c where c.rental.id = :rentalId order by c.createdAt desc")
    List<Contract> findAllByRentalIdOrderByCreatedAtDesc(@Param("rentalId") UUID rentalId);

    @Query("""
        select c from Contract c
        where c.status = 'SIGNED'
          and c.rental.status = 'active'
        """)
    List<Contract> findActiveSignedContracts();
}
