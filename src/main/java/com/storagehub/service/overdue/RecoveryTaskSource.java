package com.storagehub.service.overdue;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver-owned durable work item; it does not assert inventory, access or unit release. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled",havingValue="true")
@Transactional(propagation=Propagation.MANDATORY)
public class RecoveryTaskSource implements OverdueSources.RecoverySource {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final RentalPeriodResolver periods;
    private final ObjectProvider<OverdueSources.TermSource> terms;

    /** Only the local shared-DB writer uses this bounded check; it does not certify external Recovery coverage. */
    public void requireNoPersistedHandoff(Rental rental,Instant now) {
        requireContext(rental,now);
        Long count=jdbc.queryForObject("select count(*) from recovery_tasks where rental_id=?",Long.class,bytes(rental.getId()));
        if(count==null||count<0)throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery task check unavailable");
        if(count>0)throw ApiExceptions.conflict("Recovery blocks Renewal signing");
    }

    @Override
    @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public boolean atomic() {
        var ds=jdbc.getDataSource();
        return TransactionSynchronizationManager.isActualTransactionActive() && ds!=null
            && TransactionSynchronizationManager.hasResource(ds);
    }

    @Override
    public Optional<OverdueSources.RecoveryState> state(Rental rental,Instant now) {
        requireContext(rental,now);
        var rows=jdbc.query("select facility_id,status from recovery_tasks where rental_id=?",
            (rs,i)->new State(uuid(rs.getBytes("facility_id")),rs.getString("status")),bytes(rental.getId()));
        if(rows.isEmpty())return Optional.empty(); // Local absence cannot certify an external Recovery state.
        var row=rows.getFirst();
        if(!row.facilityId().equals(rental.getFacility().getId()))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery task facility mismatch");
        if("CLOSED".equals(row.status()))return Optional.empty();
        if(!"OPEN".equals(row.status())&&!"IN_PROGRESS".equals(row.status()))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery task status is invalid");
        return Optional.of(new OverdueSources.RecoveryState(rental.getId(),row.facilityId(),now,true));
    }

    @Override
    public OverdueSources.Handoff receive(Rental rental,String caseRef,String reason,String key,Instant now) {
        requireContext(rental,now);
        if(!atomic()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery task requires a writable shared transaction");
        String expected="RENTAL_TERM:"+rental.getId();
        if(!expected.equals(caseRef)||reason==null||reason.isBlank()||reason.length()>2000||key==null||key.isBlank())
            throw ApiExceptions.conflict("Recovery task command is incomplete");
        var locked=em.find(Rental.class,rental.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(locked==null)throw ApiExceptions.notFound("Recovery rental not found");
        em.refresh(locked,LockModeType.PESSIMISTIC_WRITE);
        if(!locked.getFacility().getId().equals(rental.getFacility().getId())
            ||locked.getStatus()!=RentalStatus.active||locked.getActualReturnedAt()!=null
            ||locked.getCompletedAt()!=null||locked.getStorageUnit().getStatus()!=StorageUnitStatus.occupied)
            throw ApiExceptions.conflict("Rental is not eligible for Recovery handoff");
        var period=periods.read(locked);
        var source=terms.getIfAvailable();
        var term=source==null?Optional.<OverdueSources.Term>empty():source.term(locked,now);
        if(period.isEmpty()||term.isEmpty()||term.get().recoveryStart()==null
            ||!period.get().lastPermittedDate().equals(term.get().lastPermittedDate())
            ||now.isBefore(term.get().recoveryStart()))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery start has not been verified");
        Long openReturn=em.createQuery("select count(c) from ReturnCase c where c.rental.id=:id and c.status<>:completed",Long.class)
            .setParameter("id",locked.getId()).setParameter("completed",ReturnCaseStatus.completed).getSingleResult();
        if(openReturn>0)throw ApiExceptions.conflict("Return case requires reconciliation before Recovery");
        var existing=jdbc.query("select id,case_ref,facility_id from recovery_tasks where rental_id=? for update",
            (rs,i)->new Existing(uuid(rs.getBytes("id")),rs.getString("case_ref"),uuid(rs.getBytes("facility_id"))),
            bytes(locked.getId()));
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(!row.caseRef().equals(caseRef)||!row.facilityId().equals(locked.getFacility().getId()))
                throw ApiExceptions.conflict("Recovery task identity mismatch");
            return new OverdueSources.Handoff(row.id(),"ALREADY_RECEIVED");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("insert into recovery_tasks(id,case_ref,rental_id,facility_id,status,reason,received_at) values(?,?,?,?,?,?,?)",
            bytes(id),caseRef,bytes(locked.getId()),bytes(locked.getFacility().getId()),"OPEN",reason,Timestamp.from(now));
        return new OverdueSources.Handoff(id,"RECEIVED");
    }

    private static void requireContext(Rental rental,Instant now) {
        if(rental==null||rental.getId()==null||rental.getFacility()==null||rental.getFacility().getId()==null||now==null)
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery context is incomplete");
    }
    private record State(UUID facilityId,String status) {}
    private record Existing(UUID id,String caseRef,UUID facilityId) {}
    private static byte[] bytes(UUID id) {return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private static UUID uuid(byte[] value) {var b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong());}
}
