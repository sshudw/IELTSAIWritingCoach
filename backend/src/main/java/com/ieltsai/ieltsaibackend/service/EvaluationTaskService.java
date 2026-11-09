package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.*;
import com.ieltsai.ieltsaibackend.entity.*;
import com.ieltsai.ieltsaibackend.mapper.EvaluationTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;

@Service
public class EvaluationTaskService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationTaskService.class);
    private static final int IDEMPOTENCY_READ_ATTEMPTS = 6;
    private static final long IDEMPOTENCY_INITIAL_DELAY_MILLIS = 10L;
    private final EvaluationTaskMapper taskMapper;
    private final EvaluationTaskPersistenceService persistence;
    private final EssayService essayService;

    public EvaluationTaskService(EvaluationTaskMapper taskMapper,
                                 EvaluationTaskPersistenceService persistence, EssayService essayService) {
        this.taskMapper = taskMapper;
        this.persistence = persistence;
        this.essayService = essayService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public EvaluationTask submit(Long userId, String key, CreateEssayRequest request) {
        EvaluationTask existing = taskMapper.findByKey(userId, key);
        if (existing != null) {
            return checkSameRequest(existing, request);
        }
        try {
            EvaluationTask task = persistence.create(userId, key, request);
            log.info("event=evaluation_task_submitted userId={} essayId={} taskId={}",
                    userId, task.getEssayId(), task.getId());
            return task;
        } catch (DuplicateKeyException exception) {
            existing = awaitConcurrentSubmission(userId, key, exception);
            return checkSameRequest(existing, request);
        }
    }

    @Transactional(readOnly = true)
    public EvaluationTask findExisting(Long userId, String key, CreateEssayRequest request) {
        EvaluationTask existing = taskMapper.findByKey(userId, key);
        return existing == null ? null : checkSameRequest(existing, request);
    }

    private EvaluationTask awaitConcurrentSubmission(Long userId, String key, DuplicateKeyException original) {
        long delayMillis = IDEMPOTENCY_INITIAL_DELAY_MILLIS;
        for (int attempt = 0; attempt < IDEMPOTENCY_READ_ATTEMPTS; attempt++) {
            // A new transaction also gives MyBatis a fresh first-level cache. Reusing the
            // pre-insert read session here could keep returning its earlier null result.
            EvaluationTask existing = persistence.findByKey(userId, key);
            if (existing != null) {
                return existing;
            }
            if (attempt == IDEMPOTENCY_READ_ATTEMPTS - 1) {
                break;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                original.addSuppressed(interrupted);
                throw original;
            }
            delayMillis *= 2;
        }
        throw original;
    }

    private EvaluationTask checkSameRequest(EvaluationTask task, CreateEssayRequest request) {
        Essay essay = essayService.getMyEssayById(task.getUserId(), task.getEssayId());
        if (essay == null || !Objects.equals(essay.getQuestion(), request.question())
                || !Objects.equals(essay.getContent(), request.content())
                || !Objects.equals(essay.getEssayType(), request.essayType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return task;
    }

    @Transactional(readOnly = true)
    public EvaluationTaskResponse getOwned(Long userId, Long taskId) {
        EvaluationTask task = taskMapper.findOwned(taskId, userId);
        if (task == null) {
            return null;
        }
        EvaluateEssayResponse result = null;
        if (task.getStatus() == EvaluationTaskStatus.COMPLETED) {
            Essay essay = essayService.getMyEssayById(userId, task.getEssayId());
            if (essay == null) {
                return null;
            }
            result = new EvaluateEssayResponse(essay.getId(), essay.getWordCount(), essay.getEvaluationStatus(),
                    essay.getOverallScore(), essay.getTaskScore(), essay.getCoherenceScore(),
                    essay.getLexicalScore(), essay.getGrammarScore(), essay.getAiFeedback(), essay.getAiModel());
        }
        return new EvaluationTaskResponse(task.getId(), task.getEssayId(), task.getStatus(), result,
                task.getStatus() == EvaluationTaskStatus.FAILED ? task.getErrorMessage() : null);
    }
}
