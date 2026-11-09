package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.AiEvaluationResult;
import com.ieltsai.ieltsaibackend.entity.*;
import com.ieltsai.ieltsaibackend.mapper.EvaluationTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.TimeUnit;

public class EvaluationTaskWorker {
    private static final Logger log = LoggerFactory.getLogger(EvaluationTaskWorker.class);
    private final EvaluationTaskMapper taskMapper;
    private final EvaluationTaskPersistenceService persistence;
    private final EssayService essayService;
    private final AiEvaluationService ai;
    private boolean recovered;
    private Outcome awaitingPersistence;

    public EvaluationTaskWorker(EvaluationTaskMapper taskMapper, EvaluationTaskPersistenceService persistence,
                                EssayService essayService, AiEvaluationService ai) {
        this.taskMapper = taskMapper;
        this.persistence = persistence;
        this.essayService = essayService;
        this.ai = ai;
    }

    // Synchronous fixed-delay + synchronized: only one in-flight AI call in this application.
    @Scheduled(fixedDelayString = "${evaluation.worker.poll-delay-ms:1000}",
               initialDelayString = "${evaluation.worker.initial-delay-ms:1000}")
    public synchronized void poll() {
        try {
            if (!recovered) {
                int count = persistence.recoverInterrupted();
                recovered = true;
                log.info("event=evaluation_tasks_recovered count={}", count);
            }
            if (awaitingPersistence != null) {
                persistOutcome();
                return;
            }
            EvaluationTask task = taskMapper.findNextPending();
            if (task == null || !persistence.claim(task.getId())) {
                return;
            }
            Essay essay;
            try {
                essay = essayService.getMyEssayById(task.getUserId(), task.getEssayId());
            } catch (RuntimeException exception) {
                // Claim committed but DB read failed. Revisit this claimed task without calling AI yet.
                awaitingPersistence = new Outcome(task, null, null, null, null);
                throw exception;
            }
            if (essay == null) {
                // Deleting the essay cascades to its task.
                return;
            }
            evaluate(task, essay);
            persistOutcome();
        } catch (RuntimeException exception) {
            // Keep the poller alive during a DB outage. No exception text is returned to clients.
            log.error("event=evaluation_worker_poll_failed", exception);
        }
    }

    private void evaluate(EvaluationTask task, Essay essay) {
        long started = System.nanoTime();
        log.info("event=ai_evaluation_started taskId={} essayId={} userId={}",
                task.getId(), essay.getId(), task.getUserId());
        try {
            AiEvaluationResult result = ai.evaluate(essay.getQuestion(), essay.getContent(), essay.getEssayType());
            awaitingPersistence = new Outcome(task, essay, result, null, null);
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                // Shutdown: leave PROCESSING durable for recovery by the next application instance.
                return;
            }
            String error = exception instanceof AiEvaluationService.AiEvaluationException aiError && aiError.isTimeout()
                    ? "AI evaluation timed out" : "AI evaluation failed";
            awaitingPersistence = new Outcome(task, essay, null, ai.getConfiguredModel(), error);
            if (exception instanceof AiEvaluationService.AiEvaluationException) {
                log.warn("event=ai_evaluation_failed taskId={} essayId={} errorType={}",
                        task.getId(), essay.getId(), exception.getClass().getSimpleName());
            } else {
                // Keep diagnostics server-side; clients only receive the fixed message above.
                log.error("event=ai_evaluation_internal_failed taskId={} essayId={}",
                        task.getId(), essay.getId(), exception);
            }
        } finally {
            log.info("event=ai_evaluation_finished taskId={} durationMs={}", task.getId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private void persistOutcome() {
        if (awaitingPersistence == null) {
            return;
        }
        Outcome outcome = awaitingPersistence;
        EvaluationTask current = taskMapper.findOwned(outcome.task().getId(), outcome.task().getUserId());
        if (current == null || current.getStatus() != EvaluationTaskStatus.PROCESSING) {
            awaitingPersistence = null;
            return;
        }
        if (outcome.essay() == null) {
            Essay essay = essayService.getMyEssayById(current.getUserId(), current.getEssayId());
            if (essay == null) {
                awaitingPersistence = null;
                return;
            }
            evaluate(current, essay);
            // Interrupted evaluation must not treat the old placeholder as an AI result.
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            outcome = awaitingPersistence;
        }
        if (outcome.result() != null) {
            persistence.complete(outcome.task(), outcome.essay(), outcome.result());
        } else {
            persistence.fail(outcome.task(), outcome.essay(), outcome.model(), outcome.error());
        }
        log.info("event=evaluation_task_finalized taskId={} status={}", outcome.task().getId(),
                outcome.result() != null ? "COMPLETED" : "FAILED");
        // If commit fails, retain the outcome and retry persistence on the next tick, not the AI call.
        awaitingPersistence = null;
    }

    private record Outcome(EvaluationTask task, Essay essay, AiEvaluationResult result, String model, String error) {}
}
