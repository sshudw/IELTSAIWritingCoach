package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.dto.ApiErrorResponse;
import com.ieltsai.ieltsaibackend.dto.CreateEssayRequest;
import com.ieltsai.ieltsaibackend.dto.EvaluationTaskResponse;
import com.ieltsai.ieltsaibackend.entity.Essay;
import com.ieltsai.ieltsaibackend.service.EssayService;
import com.ieltsai.ieltsaibackend.service.EvaluationSubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.net.URI;

@RestController
@RequestMapping("/api/essays")
public class EssayController {

    private static final int MAX_QUESTION_LENGTH = 10_000;
    private static final int MAX_CONTENT_LENGTH = 15_000;

    private final EssayService essayService;
    private final EvaluationSubmissionService submissionService;

    public EssayController(EssayService essayService, EvaluationSubmissionService submissionService) {
        this.essayService = essayService;
        this.submissionService = submissionService;
    }

    @PostMapping
    public ResponseEntity<?> createEssay(
            @Valid @RequestBody CreateEssayRequest essayRequest,
            HttpServletRequest request
    ) {
        Long currentUserId = getCurrentUserId(request);

        essayService.createEssay(currentUserId, essayRequest);
        return ResponseEntity.ok("Essay created successfully");
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluateEssay(
            @Valid @RequestBody CreateEssayRequest essayRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        Long currentUserId = getCurrentUserId(request);
        String validationError = validateEvaluationRequest(essayRequest);
        if (validationError != null) {
            return ResponseEntity.badRequest()
                    .body(ApiErrorResponse.of(400, validationError));
        }
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(400,
                    "Idempotency-Key is required (1-128 letters, digits, '.', '_', ':' or '-')"));
        }

        CreateEssayRequest normalizedRequest = new CreateEssayRequest(
                essayRequest.question(),
                essayRequest.content(),
                normalizeEssayType(essayRequest.essayType())
        );
        var task = submissionService.submit(currentUserId, idempotencyKey, normalizedRequest);
        return ResponseEntity.accepted()
                .location(URI.create("/api/evaluation-tasks/" + task.getId()))
                .body(EvaluationTaskResponse.accepted(task));
    }

    @GetMapping
    public ResponseEntity<?> getMyEssays(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request
    ) {
        Long currentUserId = getCurrentUserId(request);
        if (page < 1) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(400, "page must be at least 1"));
        }
        if (pageSize != 10) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(400, "pageSize must be 10"));
        }

        return ResponseEntity.ok(essayService.getMyEssays(currentUserId, page, pageSize));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMyEssayById(@PathVariable Long id, HttpServletRequest request) {
        Long currentUserId = getCurrentUserId(request);

        Essay essay = essayService.getMyEssayById(currentUserId, id);
        if (essay == null) {
            return ResponseEntity.status(404).body(ApiErrorResponse.of(404, "Essay not found"));
        }

        return ResponseEntity.ok(essay);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteMyEssay(@PathVariable Long id, HttpServletRequest request) {
        Long currentUserId = getCurrentUserId(request);

        if (!essayService.deleteMyEssay(currentUserId, id)) {
            return ResponseEntity.status(404).body(ApiErrorResponse.of(404, "Essay not found"));
        }

        return ResponseEntity.ok(new MessageResponse("Essay conversation deleted successfully"));
    }

    private Long getCurrentUserId(HttpServletRequest request) {
        return (Long) request.getSession(false).getAttribute("AUTH_USER_ID");
    }

    private String validateEvaluationRequest(CreateEssayRequest request) {
        if (normalizeEssayType(request.essayType()) == null) {
            return "essayType must be TASK_1 or TASK_2";
        }
        if (request.question().length() > MAX_QUESTION_LENGTH) {
            return "question is too long";
        }
        if (request.content().length() > MAX_CONTENT_LENGTH) {
            return "content is too long";
        }
        return null;
    }

    private String normalizeEssayType(String essayType) {
        String normalized = essayType.trim()
                .toUpperCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
        if ("TASK1".equals(normalized)) {
            normalized = "TASK_1";
        } else if ("TASK2".equals(normalized)) {
            normalized = "TASK_2";
        }
        return "TASK_1".equals(normalized) || "TASK_2".equals(normalized)
                ? normalized
                : null;
    }

    public record MessageResponse(String message) {
    }

}
