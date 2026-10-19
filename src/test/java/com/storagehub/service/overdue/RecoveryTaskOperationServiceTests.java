package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.storagehub.service.overdue.RecoverySql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.overdue.RecoveryTaskCommands.*;
import com.storagehub.api.returns.ReturnInspectionRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.*;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class RecoveryTaskOperationServiceTests {
    final com.zaxxer.hikari.HikariDataSource ds=pool();
    final JdbcTemplate jdbc=new JdbcTemplate(ds);
    final TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    final EntityManager em=mock(EntityManager.class);
    final DuongResourceAccess access=mock(DuongResourceAccess.class);
    final RecoveryFileEvidence evidence=mock(RecoveryFileEvidence.class);
    final ReturnSettlementService returns=mock(ReturnSettlementService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final NotificationService notifications=mock(NotificationService.class);
    final UUID id=UUID.randomUUID(),rentalId=UUID.randomUUID(),facilityId=UUID.randomUUID(),actorId=UUID.randomUUID(),returnId=UUID.randomUUID();
    final ActorPrincipal actor=new ActorPrincipal(actorId,UUID.randomUUID(),Set.of(RoleCode.STAFF),Set.of(),Map.of());
    final Rental rental=new Rental();final ReturnCase rc=new ReturnCase();
    final Instant now=Instant.parse("2026-10-18T03:00:00Z");
    RecoveryTaskOperationService service;

    @BeforeEach void setup()throws Exception {
        jdbc.execute("create table facilities(id binary(16) primary key)");jdbc.execute("create table users(id binary(16) primary key,full_name varchar(160))");
        jdbc.execute("create table storage_units(id binary(16) primary key,code varchar(100))");
        jdbc.execute("create table rentals(id binary(16) primary key,customer_id binary(16),storage_unit_id binary(16))");
        jdbc.execute("create table return_cases(id binary(16) primary key)");jdbc.execute("create table file_assets(id binary(16) primary key)");
        for(var script:List.of("db/recovery-migration/V2026101311__create_recovery_tasks.sql","db/migration-history/legacy-recovery-1502/V2026101502__recovery_task_operations.sql")) {
            String sql=new String(new ClassPathResource(script).getInputStream().readAllBytes(),StandardCharsets.UTF_8)
                .replace("CHARACTER SET ascii COLLATE ascii_bin","").replace("ENGINE=InnoDB","");
            for(var statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        }
        var f=new Facility();ReflectionTestUtils.setField(f,"id",facilityId);rental.setFacility(f);
        var customer=new User();ReflectionTestUtils.setField(customer,"id",UUID.randomUUID());rental.setCustomer(customer);
        var unit=new StorageUnit();ReflectionTestUtils.setField(unit,"id",UUID.randomUUID());unit.setStatus(StorageUnitStatus.occupied);rental.setStorageUnit(unit);
        ReflectionTestUtils.setField(rental,"id",rentalId);rental.setStatus(RentalStatus.active);
        var user=new User();ReflectionTestUtils.setField(user,"id",actorId);
        when(access.require(any(),any(),any(),any(),any(SystemPermission[].class))).thenReturn(user);
        when(em.find(Rental.class,rentalId,LockModeType.PESSIMISTIC_WRITE)).thenReturn(rental);
        ReflectionTestUtils.setField(rc,"id",returnId);rc.setRental(rental);rc.setCustomer(customer);rc.setFacility(f);rc.setStorageUnit(unit);rc.setStatus(ReturnCaseStatus.requested);
        when(em.find(ReturnCase.class,returnId)).thenReturn(rc);
        when(em.find(ReturnCase.class,returnId,LockModeType.PESSIMISTIC_WRITE)).thenReturn(rc);
        when(returns.createRecoveryReturn(actor,rentalId)).thenReturn(rc);
        when(returns.performInspection(eq(actor),eq(returnId),any())).thenAnswer(inv->{rc.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);rental.setStatus(RentalStatus.return_inspection);return null;});
        jdbc.update("insert into facilities values(?)",bytes(facilityId));jdbc.update("insert into users values(?,?)",bytes(actorId),"Staff");
        jdbc.update("insert into users values(?,?)",bytes(customer.getId()),"Customer");jdbc.update("insert into storage_units values(?,?)",bytes(unit.getId()),"S1");
        jdbc.update("insert into rentals values(?,?,?)",bytes(rentalId),bytes(customer.getId()),bytes(unit.getId()));jdbc.update("insert into return_cases values(?)",bytes(returnId));
        jdbc.update("insert into recovery_tasks values(?,?,?,?,?,?,?)",bytes(id),"RENTAL_TERM:"+rentalId,bytes(rentalId),bytes(facilityId),"OPEN","OVERDUE_DAY_8",Timestamp.from(now));
        var beans=new StaticListableBeanFactory();beans.addBean("clock",Clock.fixed(now,ZoneOffset.UTC));
        service=new RecoveryTaskOperationService(em,jdbc,new RecoveryTaskReadService(jdbc,access),access,evidence,returns,audit,notifications,
            new ObjectMapper().findAndRegisterModules(),beans.getBeanProvider(Clock.class));
    }
    @AfterEach void shutdown(){ds.close();}
    private static com.zaxxer.hikari.HikariDataSource pool(){var ds=new com.zaxxer.hikari.HikariDataSource();ds.setJdbcUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");ds.setMaximumPoolSize(4);ds.setMinimumIdle(1);return ds;}

    @Test void startReplaysButChangedPayloadStaleVersionAndRevokedPermissionCannotWrite() {
        var request=new Start(0L,"Start inventory");var result=tx.execute(s->service.start(actor,RoleCode.STAFF,id,request,"start"));
        assertThat(result.status()).isEqualTo("IN_PROGRESS");assertThat(result.expectedVersion()).isEqualTo(1);
        var replay=tx.execute(s->service.start(actor,RoleCode.STAFF,id,request,"start"));assertThat(replay).isEqualTo(result);
        assertThatThrownBy(()->tx.execute(s->service.start(actor,RoleCode.STAFF,id,new Start(0L,"Changed"),"start"))).hasMessageContaining("Idempotency");
        assertThatThrownBy(()->tx.execute(s->service.start(actor,RoleCode.STAFF,id,request,"other"))).hasMessageContaining("version changed");
        doThrow(ApiExceptions.forbidden("Revoked")).when(access).require(eq(actor),eq(RoleCode.STAFF),eq(facilityId),eq(FacilityScopeLevel.OPERATE),any(SystemPermission[].class));
        assertThatThrownBy(()->tx.execute(s->service.start(actor,RoleCode.STAFF,id,request,"start"))).hasMessageContaining("Revoked");
        assertThat(jdbc.queryForObject("select count(*) from recovery_task_commands",Long.class)).isEqualTo(1);
    }
    @Test void inventoryCreatesExactlyOneReturnAndKeepsUnitOccupied() {
        var result=inventory();assertThat(result.returnCaseId()).isEqualTo(returnId);
        assertThat(result.settlementStatus()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(rental.getStorageUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        var body=inspection();var replay=tx.execute(s->service.inspect(actor,RoleCode.STAFF,id,body,"inspect"));assertThat(replay).isEqualTo(result);
        verify(returns,times(1)).createRecoveryReturn(actor,rentalId);
        assertThatThrownBy(()->tx.execute(s->service.complete(actor,RoleCode.STAFF,id,completion(2L,UUID.randomUUID()),"close"))).hasMessageContaining("settlement has not been completed");
        verify(returns,never()).finalizeRecoveryReturn(any(),any());
    }
    @Test void settlementUsesServerAmountAndRequiresBoundReceiptBeforePhysicalCompletion() {
        inventory();rc.setStatus(ReturnCaseStatus.payment_due);rc.setAmountDueFromCustomer(new BigDecimal("950000"));
        UUID receipt=UUID.randomUUID();jdbc.update("insert into file_assets values(?)",bytes(receipt));
        when(returns.completeRecoverySettlement(actor,returnId,"BANK-1")).thenAnswer(inv->{rc.setStatus(ReturnCaseStatus.completed);return null;});
        var result=tx.execute(s->service.settle(actor,RoleCode.STAFF,id,new Settlement(2L,PaymentMethod.BANK_TRANSFER,"BANK-1",receipt,true),"settle"));
        assertThat(result.expectedVersion()).isEqualTo(3);assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select amount from recovery_settlement_receipts",BigDecimal.class)).isEqualByComparingTo("950000");
        verify(evidence).require(actor,id,"SETTLEMENT",List.of(receipt));
        assertThat(rental.getStorageUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        UUID report=UUID.randomUUID();jdbc.update("insert into file_assets values(?)",bytes(report));
        var closed=tx.execute(s->service.complete(actor,RoleCode.STAFF,id,completion(3L,report),"close"));
        assertThat(closed.status()).isEqualTo("CLOSED");assertThat(closed.assetsCleared()).isTrue();verify(returns).finalizeRecoveryReturn(actor,returnId);
        var replay=tx.execute(s->service.complete(actor,RoleCode.STAFF,id,completion(3L,report),"close"));assertThat(replay).isEqualTo(closed);
    }
    @Test void failedReleaseRollsBackTaskClosureAndCommandRecord() {
        inventory();rc.setStatus(ReturnCaseStatus.completed);
        UUID report=UUID.randomUUID();jdbc.update("insert into file_assets values(?)",bytes(report));
        doThrow(ApiExceptions.conflict("Unit no longer ready")).when(returns).finalizeRecoveryReturn(actor,returnId);
        assertThatThrownBy(()->tx.execute(s->service.complete(actor,RoleCode.STAFF,id,completion(2L,report),"close"))).hasMessageContaining("no longer ready");
        var view=service.read(actor,RoleCode.STAFF,id);assertThat(view.status()).isEqualTo("IN_PROGRESS");assertThat(view.assetsCleared()).isFalse();assertThat(view.expectedVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from recovery_task_commands where operation='COMPLETION'",Long.class)).isZero();
        verifyNoInteractions(notifications);
    }
    private RecoveryTaskOperationService.View inventory(){tx.execute(s->service.start(actor,RoleCode.STAFF,id,new Start(0L,"Start"),"start"));return tx.execute(s->service.inspect(actor,RoleCode.STAFF,id,inspection(),"inspect"));}
    private Inspection inspection(){return new Inspection(1L,new ReturnInspectionRequest(InventoryMatch.match,DamageClassification.no_damage,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"Inventory",List.of(),true,true,true,StorageUnitStatus.available),List.of(UUID.nameUUIDFromBytes("inventory".getBytes(StandardCharsets.UTF_8))));}
    private Completion completion(long version,UUID file){return new Completion(version,true,true,file,"Assets cleared and unit inspected");}
}
