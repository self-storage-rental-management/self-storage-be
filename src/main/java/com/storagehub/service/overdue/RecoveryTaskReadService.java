package com.storagehub.service.overdue;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scoped queue for the handoffs that the Recovery receiver has actually persisted. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled",havingValue="true")
public class RecoveryTaskReadService {
    private final JdbcTemplate jdbc;
    private final DuongResourceAccess access;
    public record View(UUID id,String caseRef,UUID rentalId,UUID facilityId,String status,String reason,Instant receivedAt,
        String customerName,String storageUnitCode) {}
    private static final String SELECT="select t.id,t.case_ref,t.rental_id,t.facility_id,t.status,t.reason,t.received_at,"
        +"u.full_name as customer_name,s.code as storage_unit_code from recovery_tasks t "
        +"join rentals r on r.id=t.rental_id join users u on u.id=r.customer_id "
        +"join storage_units s on s.id=r.storage_unit_id ";

    public PageResponse<View> list(ActorPrincipal actor,RoleCode role,UUID facilityId,int page,int size,String correlation) {
        scope(actor,role,facilityId);
        if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)
            throw ApiExceptions.validation("Invalid pagination",null);
        var rows=jdbc.query(SELECT+"where t.facility_id=? order by t.received_at desc,t.id desc limit ? offset ?",
            (rs,i)->view(rs),bytes(facilityId),size,page*size);
        long total=jdbc.queryForObject("select count(*) from recovery_tasks where facility_id=?",Long.class,bytes(facilityId));
        return new PageResponse<>(rows,new PageResponse.Pagination(page,size,total,
            (int)((total+size-1)/size),"receivedAt,desc"),correlation);
    }

    public View detail(ActorPrincipal actor,RoleCode role,UUID id) {
        access.require(actor,role,null,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
        var rows=jdbc.query(SELECT+"where t.id=?",(rs,i)->view(rs),bytes(id));
        if(rows.isEmpty())throw ApiExceptions.notFound("Recovery task not found");
        var result=rows.getFirst();
        scope(actor,role,result.facilityId());
        return result;
    }

    /** Internal projection only; caller must authorize Rental ownership/scope before using it. */
    public Optional<View> forRental(UUID rentalId) {
        return jdbc.query(SELECT+"where t.rental_id=?",(rs,i)->view(rs),bytes(rentalId)).stream().findFirst();
    }

    private void scope(ActorPrincipal actor,RoleCode role,UUID facilityId) {
        access.require(actor,role,facilityId,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
    }
    private static View view(java.sql.ResultSet rs)throws java.sql.SQLException {
        Timestamp at=rs.getTimestamp("received_at");
        return new View(uuid(rs.getBytes("id")),rs.getString("case_ref"),uuid(rs.getBytes("rental_id")),
            uuid(rs.getBytes("facility_id")),rs.getString("status"),rs.getString("reason"),at.toInstant(),
            rs.getString("customer_name"),rs.getString("storage_unit_code"));
    }
    private static byte[] bytes(UUID id) {return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private static UUID uuid(byte[] value) {var b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong());}
}
