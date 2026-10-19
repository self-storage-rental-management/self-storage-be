package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import com.storagehub.service.rental.period.RentalPeriodResolver;

class RecoveryTaskReadServiceTests {
    private final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"));
    private final DuongResourceAccess access=mock(DuongResourceAccess.class);
    private final RecoveryTaskReadService service=new RecoveryTaskReadService(jdbc,access);
    private final UUID facility=UUID.randomUUID(),otherFacility=UUID.randomUUID();
    private final ActorPrincipal actor=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(),Map.of());
    private final Instant now=Instant.parse("2026-10-18T00:00:00Z");

    @BeforeEach void schema() {
        jdbc.execute("create table users(id binary(16) primary key,full_name varchar(160))");
        jdbc.execute("create table storage_units(id binary(16) primary key,code varchar(100))");
        jdbc.execute("create table rentals(id binary(16) primary key,customer_id binary(16),storage_unit_id binary(16))");
        jdbc.execute("create table recovery_tasks(id binary(16) primary key,case_ref varchar(120),rental_id binary(16),facility_id binary(16),status varchar(24),reason varchar(2000),received_at timestamp)");
    }
    @AfterEach void close() {jdbc.execute("shutdown");}

    @Test void queueUsesPersistedTasksAndKeepsFacilityScopeBeforePagination() {
        task(facility,now.minusSeconds(1),"Older");
        var newer=task(facility,now,"Newer");
        task(otherFacility,now.plusSeconds(1),"Other facility");
        var page=service.list(actor,RoleCode.MANAGER,facility,0,1,"test");
        assertThat(page.data()).hasSize(1);
        assertThat(page.data().getFirst().id()).isEqualTo(newer.id());
        assertThat(page.data().getFirst().customerName()).isEqualTo("Newer");
        assertThat(page.data().getFirst().storageUnitCode()).isEqualTo("UNIT-01");
        assertThat(page.pagination().totalItems()).isEqualTo(2);
        verify(access).require(actor,RoleCode.MANAGER,facility,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
        assertThat(service.list(actor,RoleCode.MANAGER,facility,1,1,"test").data()).hasSize(1);
    }
    @Test void taskDetailsAndRiskProjectionReferToTheSameReceipt() {
        var expected=task(facility,now,"Customer");
        var detail=service.detail(actor,RoleCode.MANAGER,expected.id());
        assertThat(detail).isEqualTo(service.forRental(expected.rentalId()).orElseThrow());
        assertThat(detail.receivedAt()).isEqualTo(now);
        verify(access).require(actor,RoleCode.MANAGER,facility,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
        assertThat(service.forRental(UUID.randomUUID())).isEmpty();
    }
    @Test void deniedFacilityAndInvalidPaginationCannotReturnRows() {
        doThrow(ApiExceptions.notFound("Resource not found")).when(access)
            .require(actor,RoleCode.MANAGER,otherFacility,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
        assertThatThrownBy(()->service.list(actor,RoleCode.MANAGER,otherFacility,0,20,"test")).hasMessageContaining("Resource not found");
        assertThatThrownBy(()->service.list(actor,RoleCode.MANAGER,facility,-1,20,"test")).hasMessageContaining("pagination");
        assertThatThrownBy(()->service.list(actor,RoleCode.MANAGER,facility,0,101,"test")).hasMessageContaining("pagination");
    }
    @Test void localWriterAcceptsNoTaskButCannotResumeARecordedHandoff() {
        var source=new RecoveryTaskSource(jdbc,mock(EntityManager.class),mock(RentalPeriodResolver.class),
            new StaticListableBeanFactory().getBeanProvider(OverdueSources.TermSource.class));
        var rental=new Rental();ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());
        var f=new Facility();ReflectionTestUtils.setField(f,"id",facility);rental.setFacility(f);
        assertThatCode(()->source.requireNoPersistedHandoff(rental,now)).doesNotThrowAnyException();
        var task=task(facility,now,"Customer");ReflectionTestUtils.setField(rental,"id",task.rentalId());
        assertThatThrownBy(()->source.requireNoPersistedHandoff(rental,now)).hasMessageContaining("Recovery blocks");
        jdbc.update("update recovery_tasks set status='CLOSED' where id=?",bytes(task.id()));
        assertThatThrownBy(()->source.requireNoPersistedHandoff(rental,now)).hasMessageContaining("Recovery blocks");
    }
    private RecoveryTaskReadService.View task(UUID facilityId,Instant at,String name) {
        UUID rental=UUID.randomUUID(),id=UUID.randomUUID(),customer=UUID.randomUUID(),unit=UUID.randomUUID();
        jdbc.update("insert into users values(?,?)",bytes(customer),name);
        jdbc.update("insert into storage_units values(?,?)",bytes(unit),"UNIT-01");
        jdbc.update("insert into rentals values(?,?,?)",bytes(rental),bytes(customer),bytes(unit));
        jdbc.update("insert into recovery_tasks values(?,?,?,?,?,?,?)",bytes(id),"RENTAL_TERM:"+rental,bytes(rental),bytes(facilityId),"OPEN","AUTO_OVERDUE_DAY_8",Timestamp.from(at));
        return new RecoveryTaskReadService.View(id,"RENTAL_TERM:"+rental,rental,facilityId,"OPEN","AUTO_OVERDUE_DAY_8",at,name,"UNIT-01");
    }
    private static byte[] bytes(UUID id) {return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
}
