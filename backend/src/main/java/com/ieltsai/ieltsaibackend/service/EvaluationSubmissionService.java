package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.dto.CreateEssayRequest;
import com.ieltsai.ieltsaibackend.entity.EvaluationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvaluationSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationSubmissionService.class);

    private final EvaluationRateLimitService rateLimitService;
    private final EvaluationDedupService dedupService;
    private final EvaluationTaskService taskService;

    public EvaluationSubmissionService(EvaluationRateLimitService rateLimitService,
                                       EvaluationDedupService dedupService,
                                       EvaluationTaskService taskService) {
        this.rateLimitService = rateLimitService;
        this.dedupService = dedupService;
        this.taskService = taskService;
    }

    public EvaluationTask submit(Long userId, String idempotencyKey, CreateEssayRequest request) {
        if (!rateLimitService.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        if (dedupService.isDuplicate(userId, idempotencyKey)) {
            // Redis is only a hint. MySQL must still confirm ownership, existence and request equality.
            EvaluationTask existing = taskService.findExisting(userId, idempotencyKey, request);
            if (existing != null) {
                log.info("event=ai_evaluation_deduplicated userId={} taskId={}", userId, existing.getId());
                return existing;
            }
        }
        return taskService.submit(userId, idempotencyKey, request);
    }
}
