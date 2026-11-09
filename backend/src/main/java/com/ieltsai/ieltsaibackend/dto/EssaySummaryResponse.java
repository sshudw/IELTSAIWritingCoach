package com.ieltsai.ieltsaibackend.dto;

import com.ieltsai.ieltsaibackend.entity.EvaluationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EssaySummaryResponse(
        Long id,
        String title,
        String essayType,
        Integer wordCount,
        BigDecimal overallScore,
        EvaluationStatus evaluationStatus,
        LocalDateTime lastActivityAt
) {
}
