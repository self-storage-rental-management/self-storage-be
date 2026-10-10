package com.storagehub.service.communication;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.common.api.ApiException;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationOutboxJdbcTests {
    static final Instant NOW=Instant.parse("2026-10-11T00:00:00Z");
    HikariDataSource ds;JdbcTemplate jdbc;NotificationOutbox store;TransactionTemplate tx;
    UUID recipient,resource,event;
    @BeforeEach void setup(){ds=new HikariDataSource();ds.setJdbcUrl("jdbc:h2:mem:duong-notice-"+UUID.randomUUID()+";MODE=MySQL");ds.setUsername("sa");ds.setPassword("");ds.setMaximumPoolSize(4);ds.setMinimumIdle(1);new ResourceDatabasePopulator(new ClassPathResource("duong-notification-test-schema.sql")).execute(ds);jdbc=new JdbcTemplate(ds);store=new NotificationOutbox(jdbc);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));recipient=UUID.randomUUID();resource=UUID.randomUUID();event=UUID.randomUUID();}
    @AfterEach void close(){ds.close();}
    UUID queue(){return tx.execute(s->store.enqueue(recipient,resource,"SUPPORT_REVIEW",event,"policy","version","Vui lòng xem kết quả xử lý.","key",NOW));}
    @Test void queueSurvivesRecreatingTheStore(){UUID id=queue();assertThat(new NotificationOutbox(new JdbcTemplate(ds)).read(id,false).orElseThrow().status()).isEqualTo("PENDING");assertThat(store.pending(NOW)).containsExactly(id);}
    @Test void replayDoesNotCreateAnotherJob(){UUID id=queue();assertThat(queue()).isEqualTo(id);assertThat(jdbc.queryForObject("select count(*) from duong_notification_outbox",Long.class)).isEqualTo(1);}
    @Test void sameKeyWithDifferentMessageIsRejected(){queue();assertThatThrownBy(()->tx.execute(s->store.enqueue(recipient,resource,"SUPPORT_REVIEW",event,"policy","version","Khác nội dung.","key",NOW))).isInstanceOf(ApiException.class);}
    @Test void failedTicketCommandRollsBackItsQueuedNotice(){assertThatThrownBy(()->tx.execute(s->{store.enqueue(recipient,resource,"SUPPORT_REVIEW",event,"policy","version","Nội dung","key",NOW);throw new IllegalStateException("Ticket command failed");})).isInstanceOf(IllegalStateException.class);assertThat(store.pending(NOW)).isEmpty();}
    @Test void persistedInboxIsNotDeliveryProof(){UUID id=queue(),notification=UUID.randomUUID();tx.executeWithoutResult(s->store.inbox(id,notification));assertThat(store.review(resource,event,"policy","version",false)).isEmpty();assertThat(store.read(id,false).orElseThrow().acknowledgedAt()).isNull();}
    @Test void onlyExactRecipientAndNotificationCanAcknowledge(){UUID id=queue(),notification=UUID.randomUUID();tx.executeWithoutResult(s->store.inbox(id,notification));for(boolean wrongRecipient:List.of(true,false))assertThatThrownBy(()->tx.execute(s->store.acknowledge(id,wrongRecipient?UUID.randomUUID():recipient,wrongRecipient?notification:UUID.randomUUID(),NOW))).isInstanceOf(ApiException.class);assertThat(store.review(resource,event,"policy","version",false)).isEmpty();}
    @Test void acknowledgementIsIdempotentAndBoundToCurrentResolutionAndPolicy(){UUID id=queue(),notification=UUID.randomUUID();tx.executeWithoutResult(s->store.inbox(id,notification));var first=tx.execute(s->store.acknowledge(id,recipient,notification,NOW));var second=tx.execute(s->store.acknowledge(id,recipient,notification,NOW.plusSeconds(1)));assertThat(second.acknowledgedAt()).isEqualTo(first.acknowledgedAt());assertThat(store.review(resource,event,"policy","version",false)).isPresent();assertThat(store.review(resource,UUID.randomUUID(),"policy","version",false)).isEmpty();assertThat(store.review(resource,event,"policy","new-version",false)).isEmpty();}
    @Test void pendingNoticeCannotBeAcknowledged(){UUID id=queue();assertThatThrownBy(()->tx.execute(s->store.acknowledge(id,recipient,UUID.randomUUID(),NOW))).isInstanceOf(ApiException.class);}
    @Test void subMillisecondAcknowledgementPrecisionIsNotLost(){UUID id=queue(),notification=UUID.randomUUID();tx.executeWithoutResult(s->store.inbox(id,notification));Instant receipt=NOW.plusNanos(123456789);tx.executeWithoutResult(s->store.acknowledge(id,recipient,notification,receipt));assertThat(new NotificationOutbox(jdbc).read(id,false).orElseThrow().acknowledgedAt()).isEqualTo(receipt);}
    @Test void payloadSeparatorsCannotDisguiseDifferentPolicyFields(){queue();assertThatThrownBy(()->tx.execute(s->store.enqueue(recipient,resource,"SUPPORT_REVIEW",event,"policy, version","other","Nội dung","key",NOW))).isInstanceOf(ApiException.class);}
    @Test void retryStateIsDurableAndDoesNotPretendSuccess(){UUID id=queue();tx.executeWithoutResult(s->store.retry(id,NOW));var fresh=new NotificationOutbox(jdbc).read(id,false).orElseThrow();assertThat(fresh.status()).isEqualTo("PENDING");assertThat(fresh.attempts()).isEqualTo(1);assertThat(store.pending(NOW)).isEmpty();assertThat(store.pending(NOW.plusSeconds(30))).contains(id);assertThat(store.review(resource,event,"policy","version",false)).isEmpty();}
    @Test void dispatcherCannotCreateAnotherInboxForTheSameJob(){UUID id=queue();tx.executeWithoutResult(s->store.inbox(id,UUID.randomUUID()));assertThatThrownBy(()->tx.executeWithoutResult(s->store.inbox(id,UUID.randomUUID()))).isInstanceOf(ApiException.class);}
    @Test void missingSchemaFailsWithoutRepair(){new CommunicationSchemaGuard(jdbc).afterPropertiesSet();var empty=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:empty-"+UUID.randomUUID(),"sa",""));assertThatThrownBy(()->new CommunicationSchemaGuard(empty).afterPropertiesSet()).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    @Test void moduleDisabledDoesNotRequireOutboxSchemaOrRegisterSources(){new ApplicationContextRunner().withUserConfiguration(NotificationOutbox.class,CommunicationPolicyService.class,CommunicationSourceAdapters.class,CommunicationSchemaGuard.class,NotificationDeliveryService.class,com.storagehub.api.support.CommunicationIntegrationController.class).run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(NotificationOutbox.class);assertThat(c).doesNotHaveBean(CommunicationSourceAdapters.class);});}
}
