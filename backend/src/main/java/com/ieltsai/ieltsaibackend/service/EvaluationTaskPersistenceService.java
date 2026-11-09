package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.AiEvaluationResult;
import com.ieltsai.ieltsaibackend.dto.CreateEssayRequest;
import com.ieltsai.ieltsaibackend.entity.*;
import com.ieltsai.ieltsaibackend.mapper.EvaluationTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationTaskPersistenceService {
    private final EvaluationTaskMapper taskMapper;
    private final EssayService essayService;

    public EvaluationTaskPersistenceService(EvaluationTaskMapper taskMapper, EssayService essayService) {
        this.taskMapper = taskMapper;
        this.essayService = essayService;
    }

    // A duplicate key rolls back BOTH inserts, so concurrent submissions never leave orphan essays.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EvaluationTask create(Long userId, String key, CreateEssayRequest request) {
        Essay essay = essayService.createPendingEssay(userId, request);
        EvaluationTask task = new EvaluationTask();
        task.setUserId(userId);
        task.setEssayId(essay.getId());
        task.setIdempotencyKey(key);
        task.setStatus(EvaluationTaskStatus.PENDING);
        task.setRetryCount(0);
        taskMapper.insert(task);
        if (task.getId() == null) {
            throw new IllegalStateException("Database did not return the task id");
        }
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public EvaluationTask findByKey(Long userId, String key) {
        return taskMapper.findByKey(userId, key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long taskId) {
        return taskMapper.claim(taskId) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverInterrupted() {
        return taskMapper.recoverInterrupted();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(EvaluationTask task, Essay essay, AiEvaluationResult result) {
        essayService.saveEvaluationResult(essay, result);
        requireFinished(taskMapper.finish(task.getId(), EvaluationTaskStatus.COMPLETED, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(EvaluationTask task, Essay essay, String model, String safeError) {
        essayService.markEvaluationFailed(essay, model);
        requireFinished(taskMapper.finish(task.getId(), EvaluationTaskStatus.FAILED, safeError));
    }

    private void requireFinished(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Task is no longer PROCESSING");
        }
    }
}
