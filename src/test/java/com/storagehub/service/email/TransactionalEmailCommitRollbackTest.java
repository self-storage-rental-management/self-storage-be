package com.storagehub.service.email;

import com.storagehub.service.email.event.EmailEvents.SendVerifyEmailEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class TransactionalEmailCommitRollbackTest {

    @MockitoBean
    private TransactionalEmailService transactionalEmailService;

    @Autowired
    private TestTransactionService testTransactionService;

    @TestConfiguration
    static class Config {
        @Bean
        public TestTransactionService testTransactionService(ApplicationEventPublisher publisher) {
            return new TestTransactionService(publisher);
        }
    }

    @Service
    public static class TestTransactionService {
        private final ApplicationEventPublisher publisher;

        public TestTransactionService(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public void executeAndCommit() {
            publisher.publishEvent(new SendVerifyEmailEvent(
                UUID.randomUUID(),
                "commit@storagehub.test",
                "User Commit",
                "http://verify.url/token",
                15
            ));
        }

        @Transactional
        public void executeAndRollback() {
            publisher.publishEvent(new SendVerifyEmailEvent(
                UUID.randomUUID(),
                "rollback@storagehub.test",
                "User Rollback",
                "http://verify.url/token",
                15
            ));
            throw new RuntimeException("Forced transaction rollback for testing");
        }
    }

    @Test
    @DisplayName("Transaction Rollback: No email is dispatched to TransactionalEmailService")
    void verifyNoEmailSentOnTransactionRollback() {
        try {
            testTransactionService.executeAndRollback();
        } catch (RuntimeException ignored) {
            // Expected exception
        }

        // Must NOT send any email when transaction rolls back
        verify(transactionalEmailService, never()).sendVerifyEmailAsync(
            any(), anyString(), anyString(), anyString(), anyInt()
        );
    }

    @Test
    @DisplayName("Transaction Commit: Email is dispatched exactly once to TransactionalEmailService")
    void verifyEmailSentExactlyOnceOnTransactionCommit() {
        testTransactionService.executeAndCommit();

        // Must send EXACTLY ONCE upon successful commit
        verify(transactionalEmailService, times(1)).sendVerifyEmailAsync(
            any(), eq("commit@storagehub.test"), eq("User Commit"), anyString(), eq(15)
        );
    }
}
