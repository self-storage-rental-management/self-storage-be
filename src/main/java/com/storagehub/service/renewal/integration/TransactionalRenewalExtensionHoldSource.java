package com.storagehub.service.renewal.integration;

import com.storagehub.api.renewal.RenewalQuoteResponse.Terms;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import com.storagehub.service.renewal.RenewalSources;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * SQL persistence participates in the caller transaction; never auto-reclaims expired holds.
 * JDBC (no new mapped Entity) keeps disabled deployments compatible with the current schema.
 */
@Component @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
@ConditionalOnProperty(name={"storagehub.integration.renewal-extension-holds.enabled","storagehub.integration.renewal-operational-checks.enabled"},havingValue="true")
public class TransactionalRenewalExtensionHoldSource implements RenewalSources.ExtensionHoldSource,RenewalOperationSources.SafetySource {
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final RentalPeriodResolver periods;
    private final RenewalOperationalGuard guard;
    private final ObjectProvider<RenewalSharedWriterProtocol> protocols;
    private final ObjectProvider<Clock> clocks;

    @Override @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public boolean participatesInTransaction() { var p=protocols.getIfAvailable();return p!=null&&p.ready(); }
    @Override @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public boolean atomic() { return participatesInTransaction(); }
    private Instant now() { var c=clocks.getIfAvailable();return (c==null?Clock.systemUTC():c).instant(); }
    private RenewalSharedWriterProtocol protocol() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw ApiExceptions.conflict("Extension hold requires a writable caller transaction");
        var p=protocols.getIfAvailable();
        if(p==null||!p.ready())throw ApiExceptions.conflict("DEFERRED_SOURCE: Shared writer lock protocol is unavailable");
        return p;
    }
    private Rental lock(Rental rental) {
        protocol();
        if(rental==null||rental.getId()==null)throw ApiExceptions.conflict("Hold rental context missing");
        var r=em.find(Rental.class,rental.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(r==null)throw ApiExceptions.notFound("Hold rental not found");
        em.refresh(r,LockModeType.PESSIMISTIC_WRITE);
        if(r.getStorageUnit()==null)throw ApiExceptions.conflict("Hold unit missing");
        em.refresh(r.getStorageUnit(),LockModeType.PESSIMISTIC_WRITE);
        var dataSource=jdbc.getDataSource();
        if(dataSource==null||!TransactionSynchronizationManager.hasResource(dataSource))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Hold JDBC datasource is not bound to the caller transaction");
        return r;
    }
    private Instant verify(Rental r,LocalDate start,LocalDate end,Instant at) {
        guard.requireNoKnownConflict(r);
        var previous=periods.require(r);
        if(start==null||end==null||!end.isAfter(start)||start.isBefore(previous.endExclusive())
            ||start.isAfter(com.storagehub.service.rental.period.RenewalEffectivePeriod.start(previous,at)))
            throw ApiExceptions.conflict("Extension hold interval changed");
        var cutoff=protocol().lockAndCheck(r,start,end,at);
        if(cutoff==null)throw ApiExceptions.conflict("DEFERRED_SOURCE: Hold Recovery cutoff missing");
        if(!at.isBefore(cutoff))throw ApiExceptions.conflict("Recovery cutoff blocks extension hold");
        return cutoff;
    }
    @Override public UUID acquire(Rental rental,UUID renewalId,LocalDate start,LocalDate end,Instant expiresAt) {
        var r=lock(rental);var at=now();var n=em.find(Renewal.class,renewalId);
        if(n==null||!Objects.equals(n.getRental().getId(),r.getId())
            ||(n.getStatus()!=RenewalStatus.pending&&n.getStatus()!=RenewalStatus.accepted))
            throw ApiExceptions.conflict("Hold is not bound to an unstarted Renewal");
        var cutoff=verify(r,start,end,at);
        if(expiresAt!=null)expiresAt=expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(expiresAt==null||!expiresAt.isAfter(at)||expiresAt.isAfter(cutoff))throw ApiExceptions.conflict("Hold expiry outside cutoff");
        var existing=jdbc.query("select * from renewal_extension_holds where renewal_id=? for update",(rs,i)->row(rs),bytes(renewalId));
        if(!existing.isEmpty()) {
            var h=existing.getFirst();
            bind(h,n);
            if(!h.start().equals(start)||!h.end().equals(end)||!h.initialExpiry().equals(expiresAt))
                throw ApiExceptions.conflict("Extension hold retry payload changed");
            active(h,at);
            return h.id();
        }
        // Expired ACTIVE rows still block until accounting reconciliation explicitly releases them.
        var overlaps=jdbc.query("select id from renewal_extension_holds where unit_id=? and state='ACTIVE' and start_date<? and end_exclusive>? order by id for update",
            (rs,i)->uuid(rs.getBytes("id")),bytes(r.getStorageUnit().getId()),java.sql.Date.valueOf(end),java.sql.Date.valueOf(start));
        if(!overlaps.isEmpty())throw ApiExceptions.conflict("Conflicting extension hold");
        var id=UUID.randomUUID();
        jdbc.update("insert into renewal_extension_holds(id,renewal_id,rental_id,unit_id,facility_id,start_date,end_exclusive,initial_expires_at,expires_at,state,version,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?)",
            bytes(id),bytes(renewalId),bytes(r.getId()),bytes(r.getStorageUnit().getId()),bytes(r.getFacility().getId()),
            java.sql.Date.valueOf(start),java.sql.Date.valueOf(end),Timestamp.from(expiresAt),Timestamp.from(expiresAt),Timestamp.from(at),Timestamp.from(at));
        return id;
    }
    @Override public Instant check(Renewal renewal,Terms terms,UUID ref,Instant at) {
        var r=lock(renewal.getRental());var h=load(ref);bind(h,renewal);active(h,at);
        if(terms==null||!Objects.equals(terms.storageUnitId(),h.unit())||!Objects.equals(terms.facilityId(),h.facility())
            ||!Objects.equals(terms.extensionStartDate(),h.start())||!Objects.equals(terms.extensionEndExclusive(),h.end())
            ||!Objects.equals(terms.oldEndDate(),r.getContractEndDate()))
            throw ApiExceptions.conflict("Accepted terms do not match extension hold");
        return verify(r,h.start(),h.end(),at);
    }
    @Override public void retain(Renewal renewal,UUID ref,Instant deadline) {
        var r=lock(renewal.getRental());var h=load(ref);bind(h,renewal);var at=now();active(h,at);
        var cutoff=verify(r,h.start(),h.end(),at);
        if(deadline!=null)deadline=deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(deadline==null||!deadline.isAfter(at)||deadline.isAfter(cutoff))throw ApiExceptions.conflict("Retained hold deadline outside cutoff");
        if(deadline.equals(h.expiry()))return;
        // This is an explicit lifecycle transition, not approval payload replay.
        jdbc.update("update renewal_extension_holds set expires_at=?,version=version+1,updated_at=? where id=?",Timestamp.from(deadline),Timestamp.from(at),bytes(ref));
    }
    @Override public void consume(Renewal renewal,UUID ref) {
        var r=lock(renewal.getRental());var h=load(ref);bind(h,renewal);var at=now();
        if(h.state().equals("CONSUMED"))return;
        active(h,at);verify(r,h.start(),h.end(),at);transition(ref,"CONSUMED",at);
    }
    @Override public void release(Renewal renewal,UUID ref) {
        lock(renewal.getRental());var h=load(ref);bind(h,renewal);
        if(h.state().equals("RELEASED"))return;
        if(!h.state().equals("ACTIVE"))throw ApiExceptions.conflict("Consumed extension hold cannot be released");
        // Caller reconciles payment first. Do not mutate physical unit status or Recovery.
        transition(ref,"RELEASED",now());
    }
    @Override public void consumePeriod(Renewal renewal,Terms terms,UUID ref,LocalDate start,LocalDate end,Instant at) {
        var r=lock(renewal.getRental());var h=load(ref);bind(h,renewal);active(h,at);
        if(terms==null||!h.start().equals(terms.extensionStartDate())||!h.end().equals(terms.extensionEndExclusive()))
            throw ApiExceptions.conflict("Accepted terms do not match extension hold");
        var interval=com.storagehub.service.rental.period.RenewalEffectivePeriod.at(periods.require(r),terms,at);
        if(!interval.startDate().equals(start)||!interval.endExclusive().equals(end))
            throw ApiExceptions.conflict("Effective signing period changed");
        verify(r,start,end,at);
        var overlaps=jdbc.query("select id from renewal_extension_holds where unit_id=? and id<>? and state='ACTIVE' and start_date<? and end_exclusive>? order by id for update",
            (rs,i)->uuid(rs.getBytes("id")),bytes(h.unit()),bytes(ref),java.sql.Date.valueOf(end),java.sql.Date.valueOf(start));
        if(!overlaps.isEmpty())throw ApiExceptions.conflict("Another extension hold prevents effective signing period");
        jdbc.update("update renewal_extension_holds set start_date=?,end_exclusive=?,state='CONSUMED',version=version+1,updated_at=? where id=?",
            java.sql.Date.valueOf(start),java.sql.Date.valueOf(end),Timestamp.from(at),bytes(ref));
    }
    @Override public boolean restoreSigningWindow(Renewal renewal,Terms terms,UUID ref,Instant deadline) {
        var r=lock(renewal.getRental());var h=load(ref);bind(h,renewal);var at=now();
        if(!Set.of("ACTIVE","RELEASED").contains(h.state()))throw ApiExceptions.conflict("Consumed hold cannot be restored");
        if(terms==null||!h.start().equals(terms.extensionStartDate())||!h.end().equals(terms.extensionEndExclusive()))
            throw ApiExceptions.conflict("Signing correction terms do not match hold");
        var cutoff=verify(r,h.start(),h.end(),at);
        if(deadline==null||!deadline.isAfter(at)||deadline.isAfter(cutoff))throw ApiExceptions.conflict("Corrected signing deadline outside cutoff");
        var overlaps=jdbc.query("select id from renewal_extension_holds where unit_id=? and id<>? and state='ACTIVE' and start_date<? and end_exclusive>? order by id for update",
            (rs,i)->uuid(rs.getBytes("id")),bytes(h.unit()),bytes(ref),java.sql.Date.valueOf(h.end()),java.sql.Date.valueOf(h.start()));
        if(!overlaps.isEmpty())throw ApiExceptions.conflict("Another extension hold prevents signing correction");
        jdbc.update("update renewal_extension_holds set state='ACTIVE',expires_at=?,version=version+1,updated_at=? where id=?",
            Timestamp.from(deadline),Timestamp.from(at),bytes(ref));
        return true;
    }
    private void transition(UUID ref,String state,Instant at) {
        jdbc.update("update renewal_extension_holds set state=?,version=version+1,updated_at=? where id=?",state,Timestamp.from(at),bytes(ref));
    }
    private Hold load(UUID ref) {
        if(ref==null)throw ApiExceptions.conflict("Extension hold reference missing");
        return jdbc.query("select * from renewal_extension_holds where id=? for update",(rs,i)->row(rs),bytes(ref)).stream()
            .findFirst().orElseThrow(()->ApiExceptions.conflict("Extension hold not found"));
    }
    private static void bind(Hold h,Renewal n) {
        var r=n.getRental();
        if(!Objects.equals(h.renewal(),n.getId())||!Objects.equals(h.rental(),r.getId())||!Objects.equals(h.unit(),r.getStorageUnit().getId())||!Objects.equals(h.facility(),r.getFacility().getId()))
            throw ApiExceptions.conflict("Extension hold belongs to another rental/renewal/unit");
    }
    private static void active(Hold h,Instant at) {
        if(at==null||!h.state().equals("ACTIVE")||at.isAfter(h.expiry()))throw ApiExceptions.conflict("Extension hold inactive or expired");
    }
    private record Hold(UUID id,UUID renewal,UUID rental,UUID unit,UUID facility,LocalDate start,LocalDate end,Instant initialExpiry,Instant expiry,String state) {}
    private static Hold row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Hold(uuid(rs.getBytes("id")),uuid(rs.getBytes("renewal_id")),uuid(rs.getBytes("rental_id")),uuid(rs.getBytes("unit_id")),uuid(rs.getBytes("facility_id")),
            rs.getDate("start_date").toLocalDate(),rs.getDate("end_exclusive").toLocalDate(),rs.getTimestamp("initial_expires_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),rs.getString("state"));
    }
    private static byte[] bytes(UUID id) { var b=ByteBuffer.allocate(16);b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());return b.array(); }
    private static UUID uuid(byte[] bytes) { var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong()); }
}
