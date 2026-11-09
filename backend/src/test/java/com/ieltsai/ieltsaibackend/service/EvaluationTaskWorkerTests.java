package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.AiEvaluationResult;
import com.ieltsai.ieltsaibackend.entity.*;
import com.ieltsai.ieltsaibackend.mapper.EvaluationTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationTaskWorkerTests {
    private EvaluationTaskMapper mapper;
    private EvaluationTaskPersistenceService persistence;
    private EssayService essays;
    private AiEvaluationService ai;
    private EvaluationTaskWorker worker;
    private EvaluationTask task;
    private Essay essay;
    private AiEvaluationResult result;

    @BeforeEach
    void setUp() {
        mapper = mock(EvaluationTaskMapper.class);
        persistence = mock(EvaluationTaskPersistenceService.class);
        essays = mock(EssayService.class);
        ai = mock(AiEvaluationService.class);
        worker = new EvaluationTaskWorker(mapper, persistence, essays, ai);
        task = new EvaluationTask();
        task.setId(1L); task.setEssayId(2L); task.setUserId(7L);
        task.setStatus(EvaluationTaskStatus.PROCESSING);
        essay = new Essay(); essay.setId(2L);
        result = new AiEvaluationResult(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, "feedback", "model");
        when(mapper.findNextPending()).thenReturn(task);
        when(mapper.findOwned(1L, 7L)).thenReturn(task);
        when(persistence.claim(1L)).thenReturn(true);
        when(essays.getMyEssayById(7L, 2L)).thenReturn(essay);
        when(ai.evaluate(any(), any(), any())).thenReturn(result);
    }

    @Test
    void lostClaimDoesNotCallAi() {
        when(persistence.claim(1L)).thenReturn(false);
        worker.poll();
        verifyNoInteractions(ai);
    }

    @Test
    void failedCommitRetriesOnlyPersistenceWithoutRepeatingAi() {
        doThrow(new DataAccessResourceFailureException("DB down")).doNothing()
                .when(persistence).complete(task, essay, result);
        worker.poll();
        worker.poll();
        verify(ai, times(1)).evaluate(any(), any(), any());
        verify(persistence, times(2)).complete(task, essay, result);
        verify(persistence, times(1)).claim(1L);
    }

    @Test
    void databaseReadFailureAfterClaimIsRetried() {
        when(essays.getMyEssayById(7L, 2L))
                .thenThrow(new DataAccessResourceFailureException("DB down")).thenReturn(essay);
        worker.poll();
        verifyNoInteractions(ai);
        worker.poll();
        verify(ai, times(1)).evaluate(any(), any(), any());
        verify(persistence).complete(task, essay, result);
    }

    @Test
    void failedStartupRecoveryIsRetriedBeforeClaiming() {
        when(persistence.recoverInterrupted())
                .thenThrow(new DataAccessResourceFailureException("DB down")).thenReturn(1);
        worker.poll();
        verify(persistence, never()).claim(anyLong());
        worker.poll();
        verify(persistence, times(2)).recoverInterrupted();
        verify(persistence).complete(task, essay, result);
    }

    @Test
    void deletedTaskDoesNotPersistResult() {
        when(mapper.findOwned(1L, 7L)).thenReturn(null);
        worker.poll();
        verify(persistence, never()).complete(any(), any(), any());
        verify(persistence, never()).fail(any(), any(), any(), any());
    }

    @Test
    void unknownExceptionDoesNotLeakSensitiveDetails() {
        when(ai.evaluate(any(), any(), any())).thenThrow(new IllegalStateException("secret"));
        worker.poll();
        verify(persistence).fail(eq(task), eq(essay), isNull(), eq("AI evaluation failed"));
    }

    @Test
    void shutdownInterruptionLeavesProcessingForRestart() {
        when(ai.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new AiEvaluationService.AiEvaluationException("interrupted", false);
        });
        try {
            worker.poll();
            assertTrue(Thread.currentThread().isInterrupted());
            verify(persistence, never()).complete(any(), any(), any());
            verify(persistence, never()).fail(any(), any(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }
}
