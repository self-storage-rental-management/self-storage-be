package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.storagehub.service.overdue.RecoverySql.*;
import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

class RecoverySettlementGuardTests {
    final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"));
    final EntityManager em=mock(EntityManager.class);
    final RecoverySettlementGuard guard=new RecoverySettlementGuard(jdbc,em);
    final ReturnCase rc=new ReturnCase();final Rental rental=new Rental();final StorageUnit unit=new StorageUnit();
    @BeforeEach void setup() {
        jdbc.execute("create table recovery_tasks(id binary(16),rental_id binary(16),facility_id binary(16),status varchar(24))");
        jdbc.execute("create table recovery_task_operations(task_id binary(16),return_case_id binary(16),assets_cleared_at timestamp,completion_file_id binary(16),completed_by binary(16),completed_at timestamp)");
        jdbc.execute("create table recovery_settlement_receipts(return_case_id binary(16),transaction_reference varchar(64),direction varchar(16),amount decimal(14,2))");
        var facility=new Facility();ReflectionTestUtils.setField(facility,"id",UUID.randomUUID());rental.setFacility(facility);
        ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());rental.setStatus(RentalStatus.return_inspection);
        ReflectionTestUtils.setField(unit,"id",UUID.randomUUID());unit.setStatus(StorageUnitStatus.occupied);rental.setStorageUnit(unit);
        ReflectionTestUtils.setField(rc,"id",UUID.randomUUID());rc.setRental(rental);rc.setFacility(facility);rc.setStorageUnit(unit);rc.setStatus(ReturnCaseStatus.completed);rc.setInspectedAt(Instant.now());
        UUID task=UUID.randomUUID();jdbc.update("insert into recovery_tasks values(?,?,?,'IN_PROGRESS')",bytes(task),bytes(rental.getId()),bytes(facility.getId()));
        jdbc.update("insert into recovery_task_operations(task_id,return_case_id) values(?,?)",bytes(task),bytes(rc.getId()));
        when(em.find(StorageUnit.class,unit.getId(),LockModeType.PESSIMISTIC_WRITE)).thenReturn(unit);
    }
    @AfterEach void close(){
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement=connection.createStatement()) { statement.execute("shutdown"); }
            return null;
        });
    }
    @Test void financialCompletionAloneCannotReleaseTheUnit() {
        assertThat(guard.linked(rc)).isTrue();assertThatThrownBy(()->guard.requireReadyToRelease(rc)).hasMessageContaining("physical clearance");
        assertThatThrownBy(()->guard.requireManualSettlement(rc)).hasMessageContaining("transaction evidence");
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.occupied);
    }
    @SuppressWarnings("unchecked")
    @Test void physicalCompletionRequiresSettledReturnAndNoOtherOccupant() {
        jdbc.update("update recovery_tasks set status='CLOSED'");
        jdbc.update("update recovery_task_operations set assets_cleared_at=current_timestamp,completion_file_id=?,completed_by=?,completed_at=current_timestamp",bytes(UUID.randomUUID()),bytes(UUID.randomUUID()));
        var query=mock(TypedQuery.class,RETURNS_SELF);when(query.getSingleResult()).thenReturn(0L);when(em.createQuery(anyString(),eq(Long.class))).thenReturn(query);
        assertThatCode(()->guard.requireReadyToRelease(rc)).doesNotThrowAnyException();
        when(query.getSingleResult()).thenReturn(1L);assertThatThrownBy(()->guard.requireReadyToRelease(rc)).hasMessageContaining("Another Rental");
        rc.setStatus(ReturnCaseStatus.payment_due);assertThatThrownBy(()->guard.requireReadyToRelease(rc)).hasMessageContaining("settlement");
    }
    @Test void manualReceiptMustMatchServerCalculatedDirectionAndAmount() {
        rc.setStatus(ReturnCaseStatus.refund_pending);rc.setNetRefundAmount(new BigDecimal("500000"));
        jdbc.update("insert into recovery_settlement_receipts values(?,?,'REFUND',500000)",bytes(rc.getId()),"REF-1");
        assertThatCode(()->guard.requireReceipt(rc,"REF-1")).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.requireReceipt(rc,"OTHER")).hasMessageContaining("receipt required");
        rc.setNetRefundAmount(new BigDecimal("600000"));assertThatThrownBy(()->guard.requireReceipt(rc,"REF-1")).hasMessageContaining("receipt required");
    }
}
