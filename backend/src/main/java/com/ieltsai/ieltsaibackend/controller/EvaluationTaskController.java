package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.dto.ApiErrorResponse;
import com.ieltsai.ieltsaibackend.dto.EvaluationTaskResponse;
import com.ieltsai.ieltsaibackend.service.EvaluationTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evaluation-tasks")
public class EvaluationTaskController {
    private final EvaluationTaskService taskService;

    public EvaluationTaskController(EvaluationTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> get(@PathVariable Long taskId, HttpServletRequest request) {
        Long currentUserId = (Long) request.getSession(false).getAttribute("AUTH_USER_ID");
        EvaluationTaskResponse result = taskService.getOwned(currentUserId, taskId);
        return result == null
                ? ResponseEntity.status(404).body(ApiErrorResponse.of(404, "Evaluation task not found"))
                : ResponseEntity.ok(result);
    }
}
