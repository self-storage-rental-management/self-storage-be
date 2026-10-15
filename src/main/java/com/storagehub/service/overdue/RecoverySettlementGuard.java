package com.storagehub.service.overdue;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.storagehub.service.overdue.RecoverySql.*;

/** Financial completion of a linked ReturnCase never by itself releases a Recovery unit. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled",havingValue="true")
public class RecoverySettlementGuard {
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    public record Link(java.util.UUID taskId,String status,java.time.Instant completedAt) {}
    /** Called only while projecting a ReturnCase already authorized by ReturnSettlementService. */
    public Link source(ReturnCase rc) {
        var rows=jdbc.query("select t.id,t.status,o.completed_at from recovery_task_operations o join recovery_tasks t on t.id=o.task_id "
            +"where o.return_case_id=? and t.rental_id=? and t.facility_id=?",
            (rs,i)->new Link(uuid(rs.getBytes(1)),rs.getString(2),instant(rs.getTimestamp(3))),
            bytes(rc.getId()),bytes(rc.getRental().getId()),bytes(rc.getFacility().getId()));
        return rows.isEmpty()?null:rows.getFirst();
    }

    public boolean linked(ReturnCase rc) {
        return jdbc.queryForObject("select count(*) from recovery_task_operations where return_case_id=?",Long.class,bytes(rc.getId()))>0;
    }
    public void requireNoActiveRecovery(Rental rental) {
        if(jdbc.queryForObject("select count(*) from recovery_tasks where rental_id=? and status<>'CLOSED'",Long.class,bytes(rental.getId()))>0)
            throw ApiExceptions.conflict("Rental is being processed by Recovery");
    }
    public void requireActiveTask(Rental rental) {
        if(jdbc.queryForObject("select count(*) from recovery_tasks where rental_id=? and facility_id=? and status='IN_PROGRESS'",Long.class,
            bytes(rental.getId()),bytes(rental.getFacility().getId()))!=1)throw ApiExceptions.conflict("An in-progress Recovery task is required");
    }
    public void lock(ReturnCase rc) {
        if(!linked(rc))return;
        em.find(Rental.class,rc.getRental().getId(),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(rc,LockModeType.PESSIMISTIC_WRITE);
    }
    public void requireManualSettlement(ReturnCase rc) {
        if(linked(rc))throw ApiExceptions.conflict("Recovery settlement requires the Recovery receipt API with transaction evidence");
    }
    public void requireReceipt(ReturnCase rc,String reference) {
        String direction=rc.getStatus()==ReturnCaseStatus.payment_due?"COLLECTION":rc.getStatus()==ReturnCaseStatus.refund_pending?"REFUND":"INVALID";
        var amount=direction.equals("COLLECTION")?rc.getAmountDueFromCustomer():rc.getNetRefundAmount();
        Long count=jdbc.queryForObject("select count(*) from recovery_settlement_receipts where return_case_id=? and transaction_reference=? and direction=? and amount=?",
            Long.class,bytes(rc.getId()),reference,direction,amount);
        if(count==null||count!=1)throw ApiExceptions.conflict("Bound Recovery settlement receipt required");
    }
    public void requireReadyToRelease(ReturnCase rc) {
        Long count=jdbc.queryForObject("select count(*) from recovery_task_operations o join recovery_tasks t on t.id=o.task_id "
            +"where o.return_case_id=? and t.rental_id=? and t.facility_id=? and t.status='CLOSED' "
            +"and o.assets_cleared_at is not null and o.completion_file_id is not null and o.completed_by is not null and o.completed_at is not null",
            Long.class,bytes(rc.getId()),bytes(rc.getRental().getId()),bytes(rc.getFacility().getId()));
        if(count==null||count!=1||rc.getStatus()!=ReturnCaseStatus.completed||rc.getInspectedAt()==null)
            throw ApiExceptions.conflict("Recovery inventory, settlement and physical clearance must be completed before release");
        var unit=em.find(StorageUnit.class,rc.getStorageUnit().getId(),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(unit,LockModeType.PESSIMISTIC_WRITE);
        if(unit.getStatus()!=StorageUnitStatus.occupied||rc.getRental().getStatus()!=RentalStatus.return_inspection
            ||rc.getRental().getActualReturnedAt()!=null||rc.getRental().getCompletedAt()!=null
            ||rc.getProposedUnitStatus()==null||!java.util.Set.of(StorageUnitStatus.available,StorageUnitStatus.maintenance).contains(rc.getProposedUnitStatus()))
            throw ApiExceptions.conflict("Rental or storage unit is not ready for Recovery release");
        Long competing=em.createQuery("select count(r) from Rental r where r.storageUnit.id=:unit and r.id<>:rental and r.status<>:completed and r.actualReturnedAt is null",Long.class)
            .setParameter("unit",unit.getId()).setParameter("rental",rc.getRental().getId()).setParameter("completed",RentalStatus.completed).getSingleResult();
        if(competing>0)throw ApiExceptions.conflict("Another Rental still occupies this storage unit");
    }
}
