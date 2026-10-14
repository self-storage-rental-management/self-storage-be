package com.storagehub.service.calendar;

import com.storagehub.common.api.ApiExceptions;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Locks and writes join the caller's transaction. Staff locks span facilities/application instances. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.renewal-signing-calendar.enabled",havingValue="true")
public class RenewalCalendarStore {
    private final JdbcTemplate jdbc;
    public record Booking(UUID renewalId,UUID staffId,UUID quoteId,Instant start,Instant end,String status) {}
    public record Interval(Instant start,Instant end) {}

    public boolean atomic() {
        return TransactionSynchronizationManager.isActualTransactionActive() && jdbc.getDataSource()!=null
            && TransactionSynchronizationManager.hasResource(jdbc.getDataSource());
    }
    private void writable() {
        if(!atomic()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Signing calendar requires the caller's writable transaction");
        if(!Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Signing calendar writes require explicit READ_COMMITTED isolation");
    }
    public void lockStaff(UUID id) {
        writable();
        jdbc.update("insert into renewal_calendar_staff_locks(staff_id) values(?) on duplicate key update staff_id=staff_id",bytes(id));
        jdbc.queryForObject("select staff_id from renewal_calendar_staff_locks where staff_id=? for update",byte[].class,bytes(id));
    }
    public boolean facilityActive(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select status='active' from facilities where id=?",Boolean.class,bytes(id)));
    }
    public void lockFacility(UUID id) {
        writable();
        var statuses=jdbc.query("select status from facilities where id=? for update",(rs,i)->rs.getString(1),bytes(id));
        if(statuses.size()!=1||!"active".equals(statuses.getFirst()))throw ApiExceptions.conflict("Signing facility is not active");
    }
    public List<Interval> busy(UUID staff,Instant start,Instant end,UUID excluding) {
        String lock=atomic()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()?" for update":"";
        return jdbc.query("select appointment_start,appointment_end from renewal_signing_reservations where staff_id=? and status='RESERVED' and appointment_start<? and appointment_end>? and (? is null or renewal_id<>?)"+lock,
            (rs,i)->new Interval(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant()),
            bytes(staff),Timestamp.from(end),Timestamp.from(start),excluding==null?null:bytes(excluding),excluding==null?null:bytes(excluding));
    }
    public java.util.Optional<Booking> current(UUID renewal,boolean lock) {
        if(lock)writable();
        return jdbc.query("select renewal_id,staff_id,accepted_quote_id,appointment_start,appointment_end,status from renewal_signing_reservations where renewal_id=?"+(lock?" for update":""),
            (rs,i)->new Booking(uuid(rs.getBytes(1)),uuid(rs.getBytes(2)),uuid(rs.getBytes(3)),rs.getTimestamp(4).toInstant(),rs.getTimestamp(5).toInstant(),rs.getString(6)),bytes(renewal)).stream().findFirst();
    }
    public boolean legacyAppointmentsUncovered(Instant now) {
        // A Staff may work at multiple facilities: incomplete historical coverage anywhere is UNKNOWN.
        Long count=jdbc.queryForObject("select count(*) from renewal_operation_states s join renewals n on n.id=s.id where s.phase='SIGNING' and s.appointment_end>? and not exists (select 1 from renewal_signing_reservations b where b.renewal_id=n.id and b.status='RESERVED' and b.appointment_start=s.appointment_start and b.appointment_end=s.appointment_end)",Long.class,Timestamp.from(now));
        return count==null||count>0;
    }
    public void reserve(UUID renewal,UUID facility,UUID staff,UUID quote,Instant start,Instant end,Instant now) {
        writable();
        var existing=current(renewal,true);
        if(existing.isEmpty())jdbc.update("insert into renewal_signing_reservations(renewal_id,facility_id,staff_id,accepted_quote_id,appointment_start,appointment_end,status,updated_at,version) values(?,?,?,?,?,?,'RESERVED',?,0)",
            bytes(renewal),bytes(facility),bytes(staff),bytes(quote),Timestamp.from(start),Timestamp.from(end),Timestamp.from(now));
        else jdbc.update("update renewal_signing_reservations set facility_id=?,staff_id=?,accepted_quote_id=?,appointment_start=?,appointment_end=?,status='RESERVED',updated_at=?,version=version+1 where renewal_id=?",
            bytes(facility),bytes(staff),bytes(quote),Timestamp.from(start),Timestamp.from(end),Timestamp.from(now),bytes(renewal));
    }
    public void release(UUID renewal,Instant now) {
        writable();jdbc.update("update renewal_signing_reservations set status='RELEASED',updated_at=?,version=version+1 where renewal_id=? and status='RESERVED'",Timestamp.from(now),bytes(renewal));
    }
    private static byte[] bytes(UUID id) {return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private static UUID uuid(byte[] value) {var b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong());}
}
