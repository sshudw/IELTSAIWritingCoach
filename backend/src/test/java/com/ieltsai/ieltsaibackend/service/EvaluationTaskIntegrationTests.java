package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.*;
import com.ieltsai.ieltsaibackend.entity.*;
import com.ieltsai.ieltsaibackend.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "evaluation.worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:evaluation_tasks;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:evaluation-task-schema.sql"
})
class EvaluationTaskIntegrationTests {
    @Autowired EvaluationTaskService tasks;
    @Autowired EvaluationSubmissionService submissions;
    @Autowired EvaluationTaskPersistenceService persistence;
    @Autowired EssayService essays;
    @Autowired EvaluationTaskMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiEvaluationService ai;
    @MockitoBean StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> redisValues;

    private static final CreateEssayRequest REQUEST =
            new CreateEssayRequest("Discuss transport.", "Public transport helps many people.", "TASK_2");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void cleanMemoryDatabase() {
        jdbc.update("DELETE FROM evaluation_task");
        jdbc.update("DELETE FROM essay");
        when(ai.getConfiguredModel()).thenReturn("configured-model");
        redisValues = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
    }

    @Test
    void submissionDoesNotCallAiAndSameKeyReturnsOriginalTask() {
        EvaluationTask first = tasks.submit(7L, "one", REQUEST);
        EvaluationTask duplicate = tasks.submit(7L, "one", REQUEST);
        assertEquals(first.getId(), duplicate.getId());
        assertEquals(EvaluationTaskStatus.PENDING, first.getStatus());
        assertEquals(EvaluationStatus.PENDING, essays.getMyEssayById(7L, first.getEssayId()).getEvaluationStatus());
        assertEquals(1, count("essay"));
        assertEquals(1, count("evaluation_task"));
        verify(ai, never()).evaluate(any(), any(), any());
    }

    @Test
    void keyIsScopedToUserAndCaseSensitive() {
        EvaluationTask a = tasks.submit(7L, "Key", REQUEST);
        EvaluationTask b = tasks.submit(8L, "Key", REQUEST);
        EvaluationTask c = tasks.submit(7L, "key", REQUEST);
        assertNotEquals(a.getId(), b.getId());
        assertNotEquals(a.getId(), c.getId());
        assertNull(mapper.findOwned(a.getId(), 8L));
        assertNull(tasks.getOwned(8L, a.getId()));
        assertNotNull(tasks.getOwned(7L, a.getId()));
    }

    @Test
    void sameKeyWithDifferentContentConflicts() {
        tasks.submit(7L, "one", REQUEST);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> tasks.submit(7L, "one", new CreateEssayRequest("q", "changed", "TASK_2")));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(1, count("essay"));
    }

    @Test
    void redisShortDuplicateReturnsOriginalAndCreatesOneTask() {
        EvaluationTask first = submissions.submit(7L, "redis-duplicate", REQUEST);
        when(redisValues.setIfAbsent(contains(":dedup:user:7:key:redis-duplicate"), eq("1"),
                any(Duration.class))).thenReturn(false);

        EvaluationTask duplicate = submissions.submit(7L, "redis-duplicate", REQUEST);

        assertEquals(first.getId(), duplicate.getId());
        assertEquals(1, count("essay"));
        assertEquals(1, count("evaluation_task"));
    }

    @Test
    void redisDedupKeyIsScopedByUser() {
        EvaluationTask first = submissions.submit(7L, "shared-key", REQUEST);
        EvaluationTask second = submissions.submit(8L, "shared-key", REQUEST);

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, count("essay"));
        assertEquals(2, count("evaluation_task"));
    }

    @Test
    void redisOutageFallsBackToMySqlIdempotency() {
        RedisConnectionFailureException outage = new RedisConnectionFailureException("Redis unavailable");
        when(redisValues.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenThrow(outage);

        EvaluationTask first = submissions.submit(7L, "outage", REQUEST);
        EvaluationTask duplicate = submissions.submit(7L, "outage", REQUEST);

        assertEquals(first.getId(), duplicate.getId());
        assertEquals(1, count("essay"));
        assertEquals(1, count("evaluation_task"));
    }

    @Test
    void redisDuplicateDoesNotMaskDifferentPayloadConflict() {
        EvaluationTask first = submissions.submit(7L, "redis-conflict", REQUEST);
        when(redisValues.setIfAbsent(contains(":dedup:user:7:key:redis-conflict"), eq("1"),
                any(Duration.class))).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> submissions.submit(7L, "redis-conflict",
                        new CreateEssayRequest("Different", "Changed content", "TASK_2")));

        assertEquals(409, error.getStatusCode().value());
        assertEquals(1, count("essay"));
        assertEquals(1, count("evaluation_task"));
    }

    @Test
    void duplicateInsertRollsBackItsNewEssay() {
        persistence.create(7L, "one", REQUEST);
        assertThrows(DuplicateKeyException.class, () -> persistence.create(7L, "one", REQUEST));
        assertEquals(1, count("essay"));
        assertEquals(1, count("evaluation_task"));
    }

    @Test
    void concurrentSubmissionsProduceOneTaskAndOneEssay() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return tasks.submit(7L, "same", REQUEST).getId();
                }));
            }
            start.countDown();
            Long id = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (Future<Long> future : futures) {
                assertEquals(id, future.get(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, count("essay"));
        assertEquals(1, count("evaluation_task"));
    }

    @Test
    void concurrentConditionalClaimsHaveExactlyOneWinner() throws Exception {
        Long id = tasks.submit(7L, "claim", REQUEST).getId();
        try (var pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return persistence.claim(id);
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
        }
        assertEquals(EvaluationTaskStatus.PROCESSING, mapper.findOwned(id, 7L).getStatus());
    }

    @Test
    void workerCallsAiOutsideTransactionAndPersistsScoresAtomically() {
        EvaluationTask task = tasks.submit(7L, "success", REQUEST);
        when(ai.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(EvaluationTaskStatus.PROCESSING, mapper.findOwned(task.getId(), 7L).getStatus());
            return result();
        });
        EvaluationTaskWorker worker = worker();
        worker.poll();
        worker.poll();
        EvaluationTaskResponse response = tasks.getOwned(7L, task.getId());
        assertEquals(EvaluationTaskStatus.COMPLETED, response.status());
        assertEquals(new BigDecimal("7.0"), response.result().overallScore());
        assertEquals("feedback", response.result().aiFeedback());
        assertEquals("actual-model", response.result().aiModel());
        assertEquals(5, response.result().wordCount());
        assertNotNull(mapper.findOwned(task.getId(), 7L).getCreatedAt());
        assertNotNull(mapper.findOwned(task.getId(), 7L).getUpdatedAt());
        verify(ai, times(1)).evaluate(REQUEST.question(), REQUEST.content(), REQUEST.essayType());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workerFailureIsSanitizedAndNotAutomaticallyRetried(boolean timeout) {
        EvaluationTask task = tasks.submit(7L, "failure", REQUEST);
        when(ai.evaluate(any(), any(), any()))
                .thenThrow(new AiEvaluationService.AiEvaluationException("secret/internal detail", timeout));
        worker().poll();
        worker().poll(); // simulate a restart: FAILED must stay terminal
        EvaluationTaskResponse response = tasks.getOwned(7L, task.getId());
        assertEquals(EvaluationTaskStatus.FAILED, response.status());
        assertNull(response.result());
        assertEquals(timeout ? "AI evaluation timed out" : "AI evaluation failed", response.errorMessage());
        Essay essay = essays.getMyEssayById(7L, task.getEssayId());
        assertEquals(EvaluationStatus.FAILED, essay.getEvaluationStatus());
        assertNull(essay.getOverallScore());
        verify(ai, times(1)).evaluate(any(), any(), any());
    }

    @Test
    void staleCompletionRollsBackEssayScores() {
        EvaluationTask task = tasks.submit(7L, "atomic", REQUEST);
        Essay essay = essays.getMyEssayById(7L, task.getEssayId());
        // Task is still PENDING: the final conditional update must fail and roll back score writes.
        assertThrows(IllegalStateException.class, () -> persistence.complete(task, essay, result()));
        Essay stored = essays.getMyEssayById(7L, essay.getId());
        assertEquals(EvaluationStatus.PENDING, stored.getEvaluationStatus());
        assertNull(stored.getOverallScore());
        assertThrows(IllegalStateException.class, () -> persistence.fail(task, stored, "model", "safe"));
        assertEquals(EvaluationStatus.PENDING, essays.getMyEssayById(7L, essay.getId()).getEvaluationStatus());
    }

    @Test
    void restartRecoversProcessingAndContinuesPendingWithoutReplayingCompleted() {
        EvaluationTask interrupted = tasks.submit(7L, "interrupted", REQUEST);
        EvaluationTask pending = tasks.submit(7L, "pending", REQUEST);
        assertTrue(persistence.claim(interrupted.getId()));
        when(ai.evaluate(any(), any(), any())).thenReturn(result());
        EvaluationTaskWorker restarted = worker();
        restarted.poll();
        restarted.poll();
        restarted.poll();
        assertEquals(EvaluationTaskStatus.COMPLETED, mapper.findOwned(interrupted.getId(), 7L).getStatus());
        assertEquals(1, mapper.findOwned(interrupted.getId(), 7L).getRetryCount());
        assertEquals(0, mapper.findOwned(pending.getId(), 7L).getRetryCount());
        assertEquals(EvaluationTaskStatus.COMPLETED, mapper.findOwned(pending.getId(), 7L).getStatus());
        verify(ai, times(2)).evaluate(any(), any(), any());
    }

    @Test
    void deletingEssayCascadesTaskAndQueryReturnsNotFound() {
        EvaluationTask task = tasks.submit(7L, "delete", REQUEST);
        assertTrue(essays.deleteMyEssay(7L, task.getEssayId()));
        assertNull(tasks.getOwned(7L, task.getId()));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private EvaluationTaskWorker worker() {
        return new EvaluationTaskWorker(mapper, persistence, essays, ai);
    }

    private AiEvaluationResult result() {
        return new AiEvaluationResult(new BigDecimal("6.0"), new BigDecimal("6.5"),
                new BigDecimal("7.0"), new BigDecimal("7.5"), "feedback", "actual-model");
    }
}
