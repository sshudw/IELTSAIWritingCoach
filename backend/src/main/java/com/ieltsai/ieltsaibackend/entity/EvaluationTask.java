package com.ieltsai.ieltsaibackend.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class EvaluationTask {
    private Long id;
    private Long userId;
    private Long essayId;
    private String idempotencyKey;
    private EvaluationTaskStatus status;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
