package com.ieltsai.ieltsaibackend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ieltsai.ieltsaibackend.entity.EvaluationTask;
import com.ieltsai.ieltsaibackend.entity.EvaluationTaskStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationTaskResponse(
        Long taskId, Long essayId, EvaluationTaskStatus status,
        EvaluateEssayResponse result, String errorMessage
) {
    public static EvaluationTaskResponse accepted(EvaluationTask task) {
        return new EvaluationTaskResponse(task.getId(), task.getEssayId(), task.getStatus(), null, null);
    }
}
