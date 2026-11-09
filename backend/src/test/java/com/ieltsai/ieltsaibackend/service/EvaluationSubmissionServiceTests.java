package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.CreateEssayRequest;
import com.ieltsai.ieltsaibackend.entity.EvaluationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationSubmissionServiceTests {
    private static final CreateEssayRequest REQUEST =
            new CreateEssayRequest("Question", "Essay content", "TASK_2");
    private EvaluationRateLimitService rateLimit;
    private EvaluationDedupService dedup;
    private EvaluationTaskService tasks;
    private EvaluationSubmissionService submissions;

    @BeforeEach
    void setUp() {
        rateLimit = mock(EvaluationRateLimitService.class);
        dedup = mock(EvaluationDedupService.class);
        tasks = mock(EvaluationTaskService.class);
        submissions = new EvaluationSubmissionService(rateLimit, dedup, tasks);
    }

    @Test
    void normalSubmissionPassesRateLimitAndStoresDedupMarker() {
        EvaluationTask task = task(12L);
        when(rateLimit.tryAcquire(7L)).thenReturn(true);
        when(dedup.isDuplicate(7L, "key")).thenReturn(false);
        when(tasks.submit(7L, "key", REQUEST)).thenReturn(task);

        assertSame(task, submissions.submit(7L, "key", REQUEST));
        verify(dedup).isDuplicate(7L, "key");
    }

    @Test
    void redisDuplicateReturnsMySqlTaskWithoutNewSubmission() {
        EvaluationTask task = task(12L);
        when(rateLimit.tryAcquire(7L)).thenReturn(true);
        when(dedup.isDuplicate(7L, "key")).thenReturn(true);
        when(tasks.findExisting(7L, "key", REQUEST)).thenReturn(task);

        assertSame(task, submissions.submit(7L, "key", REQUEST));

        verify(tasks, never()).submit(anyLong(), anyString(), any());
    }

    @Test
    void exceedingRateLimitReturns429BeforeMySqlSubmission() {
        when(rateLimit.tryAcquire(7L)).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> submissions.submit(7L, "key", REQUEST));

        assertEquals(429, error.getStatusCode().value());
        verifyNoInteractions(dedup);
        verify(tasks, never()).submit(anyLong(), anyString(), any());
    }

    private EvaluationTask task(Long id) {
        EvaluationTask task = new EvaluationTask();
        task.setId(id);
        return task;
    }
}
