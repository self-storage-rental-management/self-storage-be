package com.storagehub.service.overdue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.overdue.RecoveryTaskCommands.*;
import com.storagehub.api.returns.ReturnInspectionRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.*;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.storagehub.service.overdue.RecoverySql.*;

/** Rental lock serializes Recovery, Renewal and linked Return settlement commands. */
@Service @RequiredArgsConstructor @Transactional(isolation=Isolation.READ_COMMITTED)
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled",havingValue="true")
public class RecoveryTaskOperationService {
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final RecoveryTaskReadService tasks;
    private final DuongResourceAccess access;
    private final RecoveryFileEvidence evidence;
    private final ReturnSettlementService returns;
    private final AuditLogService audit;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final ObjectProvider<Clock> clocks;

    public record View(UUID taskId,String status,long expectedVersion,UUID startedBy,Instant startedAt,
        UUID returnCaseId,ReturnCaseStatus settlementStatus,List<UUID> evidenceFileIds,
        UUID receiptFileId,boolean assetsCleared,UUID completionFileId,Instant completedAt,String transactionReference) {}
    private record SettlementReceipt(UUID fileId,String reference) {}
    private record State(long version,UUID startedBy,Instant startedAt,UUID returnId,String inspection,
        Instant clearedAt,UUID completionFile,Instant completedAt) {}
    private record Context(RecoveryTaskReadService.View task,Rental rental,User actor) {}

    @Transactional(readOnly=true)
    public View read(ActorPrincipal actor,RoleCode role,UUID id) {return project(tasks.detail(actor,role,id));}

    public View start(ActorPrincipal actor,RoleCode role,UUID id,Start body,String key) {
        var c=context(actor,role,id);var replay=replay(c,key,"START",body);if(replay.isPresent())return replay.get();
        version(id,body.expectedVersion());
        if(!c.task().status().equals("OPEN"))throw ApiExceptions.conflict("Recovery task has already started or closed");
        if(body.note()==null||body.note().isBlank()||body.note().length()>2000)throw ApiExceptions.validation("Recovery start note required",null);
        if(c.rental().getStatus()!=RentalStatus.active||c.rental().getActualReturnedAt()!=null
            ||c.rental().getStorageUnit().getStatus()!=StorageUnitStatus.occupied)throw ApiExceptions.conflict("Rental is not ready for Recovery");
        jdbc.update("insert into recovery_task_operations(task_id,version,started_by,started_at) values(?,1,?,?)",bytes(id),bytes(actor.userId()),Timestamp.from(now()));
        jdbc.update("update recovery_tasks set status='IN_PROGRESS' where id=?",bytes(id));
        return finish(c,key,"START",body);
    }

    public View inspect(ActorPrincipal actor,RoleCode role,UUID id,Inspection body,String key) {
        var c=context(actor,role,id);var replay=replay(c,key,"INSPECTION",body);if(replay.isPresent())return replay.get();
        var s=version(id,body.expectedVersion());inProgress(c,s);
        if(s.returnId()!=null)throw ApiExceptions.conflict("Recovery inventory has already been recorded");
        var input=body.inspection();
        if(input==null||input.inventoryMatch()==null||input.damageClassification()==null
            ||!Set.of(StorageUnitStatus.available,StorageUnitStatus.maintenance).contains(input.proposedUnitStatus()==null?StorageUnitStatus.available:input.proposedUnitStatus()))
            throw ApiExceptions.validation("Inventory, damage classification and valid proposed unit status required",null);
        for(var fee:Arrays.asList(input.damageFee(),input.cleaningFee(),input.lostItemFee(),input.overdueFee(),input.outstandingFee()))
            if(fee==null||fee.signum()<0||fee.stripTrailingZeros().scale()>0||fee.precision()-fee.scale()>12)
                throw ApiExceptions.validation("Recovery fees must be nonnegative VND integers within the settlement limit",null);
        if(input.damageFee().add(input.cleaningFee()).add(input.lostItemFee()).add(input.overdueFee()).add(input.outstandingFee()).compareTo(new java.math.BigDecimal("999999999999"))>0)
            throw ApiExceptions.validation("Total Recovery fees exceed the settlement limit",null);
        if(input.evidencePhotos()!=null&&!input.evidencePhotos().isEmpty())throw ApiExceptions.validation("Use bound evidenceFileIds instead of external photo URLs",null);
        evidence.require(actor,id,"INSPECTION",body.evidenceFileIds());
        var rc=returns.createRecoveryReturn(actor,c.rental().getId());
        jdbc.update("update recovery_task_operations set return_case_id=?,inspection_json=?,version=version+1 where task_id=?",
            bytes(rc.getId()),json(body),bytes(id));
        var inspection=new ReturnInspectionRequest(input.inventoryMatch(),input.damageClassification(),input.damageFee(),input.cleaningFee(),
            input.lostItemFee(),input.overdueFee(),input.outstandingFee(),input.staffNotes(),
            body.evidenceFileIds().stream().map(file->"/api/files/"+file).toList(),
            input.returnedKey(),input.returnedCard(),input.returnedLock(),input.proposedUnitStatus());
        returns.performInspection(actor,rc.getId(),inspection);em.flush();
        return finish(c,key,"INSPECTION",body);
    }

    public View recalculate(ActorPrincipal actor,RoleCode role,UUID id,RecalculateSettlement body,String key) {
        var c=context(actor,role,id);var replay=replay(c,key,"RECALCULATE_SETTLEMENT",body);if(replay.isPresent())return replay.get();
        var s=version(id,body.expectedVersion());inProgress(c,s);var rc=returnCase(c,s);
        if(jdbc.queryForObject("select count(*) from recovery_settlement_receipts where task_id=?",Long.class,bytes(id))>0)
            throw ApiExceptions.conflict("Đã ghi nhận biên lai, không thể tự sửa quyết toán cũ.");
        returns.recalculateRecoverySettlement(actor,rc.getId());
        jdbc.update("update recovery_task_operations set version=version+1 where task_id=?",bytes(id));
        return finish(c,key,"RECALCULATE_SETTLEMENT",body);
    }

    public View settle(ActorPrincipal actor,RoleCode role,UUID id,Settlement body,String key) {
        var c=context(actor,role,id);
        access.require(actor,role,c.task().facilityId(),FacilityScopeLevel.OPERATE,SystemPermission.MANAGE_PAYMENTS);
        var replay=replay(c,key,"SETTLEMENT",body);if(replay.isPresent())return replay.get();
        var s=version(id,body.expectedVersion());inProgress(c,s);var rc=returnCase(c,s);
        if(!Boolean.TRUE.equals(body.confirmed())||body.paymentMethod()==null)
            throw ApiExceptions.validation("Cần chọn phương thức và xác nhận đã thực thu hoặc thực hoàn tiền.",null);
        String transactionReference=body.paymentMethod()==PaymentMethod.CASH
            ?"TM-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT):body.transactionReference();
        if(transactionReference==null||!transactionReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}"))
            throw ApiExceptions.validation("Cần nhập mã giao dịch chuyển khoản hợp lệ, tối đa 64 ký tự.",null);
        String direction=rc.getStatus()==ReturnCaseStatus.payment_due?"COLLECTION":rc.getStatus()==ReturnCaseStatus.refund_pending?"REFUND":null;
        if(direction==null)throw ApiExceptions.conflict("Settlement must first be confirmed or the dispute resolved");
        var amount=direction.equals("COLLECTION")?rc.getAmountDueFromCustomer():rc.getNetRefundAmount();
        if(amount==null||amount.signum()<=0)throw ApiExceptions.conflict("No amount to settle");
        evidence.require(actor,id,"SETTLEMENT",Collections.singletonList(body.receiptFileId()));
        try {
            jdbc.update("insert into recovery_settlement_receipts(task_id,return_case_id,direction,amount,payment_method,transaction_reference,receipt_file_id,recorded_by,recorded_at) values(?,?,?,?,?,?,?,?,?)",
                bytes(id),bytes(rc.getId()),direction,amount,body.paymentMethod().name(),transactionReference,bytes(body.receiptFileId()),bytes(actor.userId()),Timestamp.from(now()));
        }catch(DuplicateKeyException used){throw ApiExceptions.conflict("Recovery settlement or transaction evidence was already recorded");}
        returns.completeRecoverySettlement(actor,rc.getId(),transactionReference);
        jdbc.update("update recovery_task_operations set version=version+1 where task_id=?",bytes(id));
        return finish(c,key,"SETTLEMENT",body);
    }

    public View complete(ActorPrincipal actor,RoleCode role,UUID id,Completion body,String key) {
        var c=context(actor,role,id);var replay=replay(c,key,"COMPLETION",body);if(replay.isPresent())return replay.get();
        var s=version(id,body.expectedVersion());inProgress(c,s);var rc=returnCase(c,s);
        if(rc.getStatus()!=ReturnCaseStatus.completed)throw ApiExceptions.conflict("Recovery settlement has not been completed");
        if(!Boolean.TRUE.equals(body.assetsCleared())||!Boolean.TRUE.equals(body.unitInspected())||body.note()==null||body.note().isBlank())
            throw ApiExceptions.validation("Physical clearance, unit inspection and completion note required",null);
        evidence.require(actor,id,"COMPLETION",Collections.singletonList(body.reportFileId()));
        var at=Timestamp.from(now());
        jdbc.update("update recovery_task_operations set version=version+1,assets_cleared_at=?,completion_file_id=?,completed_by=?,completed_at=?,completion_note=? where task_id=?",
            at,bytes(body.reportFileId()),bytes(actor.userId()),at,body.note(),bytes(id));
        jdbc.update("update recovery_tasks set status='CLOSED' where id=?",bytes(id));
        returns.finalizeRecoveryReturn(actor,rc.getId());em.flush();
        notifications.createNotification(c.rental().getCustomer().getId(),NotificationType.OVERDUE,"Đã hoàn tất hồ sơ thu hồi kho",
            "Cơ sở đã hoàn tất kiểm kê, quyết toán và xử lý gian kho của hồ sơ thuê.",c.rental().getId());
        return finish(c,key,"COMPLETION",body);
    }

    private Context context(ActorPrincipal actor,RoleCode role,UUID id) {
        var visible=tasks.detail(actor,role,id);
        var user=access.require(actor,role,visible.facilityId(),FacilityScopeLevel.OPERATE,SystemPermission.VIEW_RENTALS,SystemPermission.PROCESS_RETURNS);
        var rental=em.find(Rental.class,visible.rentalId(),LockModeType.PESSIMISTIC_WRITE);
        if(rental==null)throw ApiExceptions.notFound("Recovery Rental not found");em.refresh(rental,LockModeType.PESSIMISTIC_WRITE);
        var rows=jdbc.query("select rental_id,facility_id from recovery_tasks where id=? for update",
            (rs,i)->List.of(uuid(rs.getBytes(1)),uuid(rs.getBytes(2))),bytes(id));
        if(rows.size()!=1||!rows.getFirst().equals(List.of(rental.getId(),rental.getFacility().getId())))throw ApiExceptions.conflict("Recovery task resource binding changed");
        return new Context(tasks.detail(actor,role,id),rental,user);
    }
    private State version(UUID task,Long expected) {
        var state=state(task);long actual=state==null?0:state.version();
        if(expected==null||expected<0||expected!=actual)throw ApiExceptions.conflict("Recovery version changed; reload before submitting");return state;
    }
    private void inProgress(Context c,State s){if(!c.task().status().equals("IN_PROGRESS")||s==null)throw ApiExceptions.conflict("Start the Recovery task before processing it");}
    private ReturnCase returnCase(Context c,State state) {
        if(state.returnId()==null)throw ApiExceptions.conflict("Recovery inventory must be recorded first");
        var rc=em.find(ReturnCase.class,state.returnId(),LockModeType.PESSIMISTIC_WRITE);
        if(rc==null||!rc.getRental().getId().equals(c.rental().getId())||!rc.getFacility().getId().equals(c.task().facilityId()))throw ApiExceptions.conflict("Recovery settlement binding invalid");
        em.refresh(rc,LockModeType.PESSIMISTIC_WRITE);return rc;
    }
    private View project(RecoveryTaskReadService.View task) {
        var state=state(task.id());if(state==null)return new View(task.id(),task.status(),0,null,null,null,null,List.of(),null,false,null,null,null);
        var rc=state.returnId()==null?null:em.find(ReturnCase.class,state.returnId());
        if(state.returnId()!=null&&(rc==null||!task.rentalId().equals(rc.getRental().getId())||!task.facilityId().equals(rc.getFacility().getId())))
            throw ApiExceptions.conflict("Recovery settlement binding invalid");
        List<UUID> files=new ArrayList<>();
        if(state.inspection()!=null)try{for(var file:mapper.readTree(state.inspection()).path("evidenceFileIds"))files.add(UUID.fromString(file.asText()));}
        catch(Exception invalid){throw ApiExceptions.conflict("Recovery inventory evidence is invalid");}
        var receipts=jdbc.query("select receipt_file_id,transaction_reference from recovery_settlement_receipts where task_id=?",(rs,i)->new SettlementReceipt(uuid(rs.getBytes(1)),rs.getString(2)),bytes(task.id()));
        return new View(task.id(),task.status(),state.version(),state.startedBy(),state.startedAt(),state.returnId(),rc==null?null:rc.getStatus(),
            List.copyOf(files),receipts.isEmpty()?null:receipts.getFirst().fileId(),state.clearedAt()!=null,state.completionFile(),state.completedAt(),receipts.isEmpty()?null:receipts.getFirst().reference());
    }
    private State state(UUID task) {
        return jdbc.query("select * from recovery_task_operations where task_id=?",(rs,i)->new State(rs.getLong("version"),uuid(rs.getBytes("started_by")),instant(rs.getTimestamp("started_at")),
            uuid(rs.getBytes("return_case_id")),rs.getString("inspection_json"),instant(rs.getTimestamp("assets_cleared_at")),uuid(rs.getBytes("completion_file_id")),instant(rs.getTimestamp("completed_at"))),bytes(task)).stream().findFirst().orElse(null);
    }
    private Optional<View> replay(Context c,String key,String operation,Object body) {
        if(key==null||key.isBlank()||key.length()>100)throw ApiExceptions.validation("Idempotency-Key (1–100 characters) required",null);
        var rows=jdbc.queryForList("select task_id,operation,payload_hash,result_json from recovery_task_commands where actor_id=? and key_hash=?",bytes(c.actor().getId()),hash(key));
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        if(!c.task().id().equals(uuid((byte[])row.get("task_id")))||!operation.equals(row.get("operation"))||!hash(json(body)).equals(row.get("payload_hash")))
            throw ApiExceptions.conflict("Idempotency key reused for a different Recovery request");
        try{return Optional.of(mapper.readValue(row.get("result_json").toString(),View.class));}catch(Exception invalid){throw ApiExceptions.conflict("Recovery command result invalid");}
    }
    private View finish(Context c,String key,String op,Object body) {
        var updated=tasks.forRental(c.rental().getId()).orElseThrow();var view=project(updated);
        try{jdbc.update("insert into recovery_task_commands(actor_id,key_hash,task_id,operation,payload_hash,result_json) values(?,?,?,?,?,?)",
            bytes(c.actor().getId()),hash(key),bytes(c.task().id()),op,hash(json(body)),json(view));}
        catch(DuplicateKeyException reused){throw ApiExceptions.conflict("Recovery idempotency key was already used");}
        audit.recordMutation(c.actor(),"RECOVERY_"+op,"RecoveryTask",c.task().id(),c.task().facilityId(),null,Map.of("request",body,"result",view));return view;
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception invalid){throw new IllegalArgumentException("Recovery payload cannot be serialized",invalid);}}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private Instant now(){var clock=clocks.getIfAvailable();return (clock==null?Clock.systemUTC():clock).instant();}
}
