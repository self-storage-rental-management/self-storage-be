package com.storagehub.service;

import static org.mockito.Mockito.*;

import com.storagehub.domain.model.ViolationRecovery;
import com.storagehub.domain.repo.ViolationRecoveryRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ViolationRecoverySchedulerTests {
    @Test
    void backfillsCustomerAndAssignmentNoticesWithoutWaitingForTheActionDeadline() {
        var repository = mock(ViolationRecoveryRepository.class);
        var workflow = mock(ViolationRecoveryWorkflow.class);
        var entry = new ViolationRecovery();
        var id = UUID.randomUUID();
        ReflectionTestUtils.setField(entry, "id", id);
        when(repository.due(any(), any())).thenReturn(List.of());
        when(repository.findByStageNotInOrderByCreatedAtDesc(anyList(), any()))
            .thenReturn(List.of(entry));
        new ViolationRecoveryScheduler(repository, workflow).process();
        verify(workflow).notifyPendingNotifications(id);
        verify(repository).findByStageNotInOrderByCreatedAtDesc(argThat(stages ->
            !stages.contains(ViolationRecovery.Stage.REVIEW_REQUIRED)
                && stages.contains(ViolationRecovery.Stage.CANCELLED)
                && stages.contains(ViolationRecovery.Stage.COMPLETED)), any());
        verify(workflow, never()).processDue(any(), any());
    }
}
