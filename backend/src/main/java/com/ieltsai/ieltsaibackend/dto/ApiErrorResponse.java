package com.ieltsai.ieltsaibackend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ieltsai.ieltsaibackend.entity.EvaluationStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        int status,
        String message,
        Long essayId,
        Long userMessageId,
        EvaluationStatus evaluationStatus,
        String aiModel
    ) {
    public static ApiErrorResponse of(int status, String message) {
        return new ApiErrorResponse(status, message, null, null, null, null);
    }

    public static ApiErrorResponse evaluation(
            int status, String message, Long essayId,
            EvaluationStatus evaluationStatus, String aiModel
    ) {
        return new ApiErrorResponse(status, message, essayId, null, evaluationStatus, aiModel);
    }

    public static ApiErrorResponse chat(int status, String message, Long userMessageId) {
        return new ApiErrorResponse(status, message, null, userMessageId, null, null);
    }
}
